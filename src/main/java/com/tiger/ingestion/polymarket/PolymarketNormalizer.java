package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiger.domain.CanonicalStatus;
import com.tiger.domain.IntervalMetadata;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PolymarketNormalizer {
    private final ObjectMapper objectMapper;

    public PolymarketNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<NormalizedEvent> normalizeEvents(JsonNode response) {
        JsonNode events = response.isArray() ? response : response.path("events");
        if (!events.isArray()) {
            return List.of();
        }
        List<NormalizedEvent> normalized = new ArrayList<>();
        for (JsonNode event : events) {
            normalized.add(normalizeEvent(event));
        }
        return normalized;
    }

    public NormalizedEvent normalizeEvent(JsonNode event) {
        String eventId = firstText(event, "id", "eventId", "slug");
        if (eventId == null) {
            throw new IllegalArgumentException("Polymarket event is missing id/eventId/slug");
        }

        List<String> tags = tags(event.path("tags"));
        List<NormalizedMarket> markets = normalizeMarkets(eventId, event.path("markets"));
        List<String> marketQuestions = markets.stream().map(NormalizedMarket::question).toList();
        int activeMarkets = (int) markets.stream().filter(m -> m.status() == CanonicalStatus.active).count();

        String sourceSeriesId = null;
        JsonNode series = event.path("series");
        if (!series.isMissingNode() && !series.isNull()) {
            sourceSeriesId = firstText(series, "id", "slug", "ticker");
        }

        return new NormalizedEvent(
                eventId,
                text(event, "ticker"),
                text(event, "slug"),
                sourceSeriesId,
                requiredTitle(event),
                text(event, "description"),
                text(event, "category"),
                tags,
                PolymarketStatusNormalizer.normalize(event),
                sourceStatusJson(event),
                timestamp(event, "startDate", "start_date"),
                timestamp(event, "endDate", "end_date"),
                markets.size(),
                activeMarkets,
                marketQuestions,
                number(event, "volume", "volumeNum"),
                number(event, "liquidity", "liquidityNum"),
                number(event, "openInterest", "open_interest"),
                text(event, "image", "imageUrl"),
                text(event, "icon", "iconUrl"),
                timestamp(event, "createdAt", "created_at"),
                timestamp(event, "updatedAt", "updated_at"),
                toJson(event),
                markets);
    }

    private List<NormalizedMarket> normalizeMarkets(String sourceEventId, JsonNode marketsNode) {
        if (!marketsNode.isArray()) {
            return List.of();
        }
        List<NormalizedMarket> markets = new ArrayList<>();
        for (JsonNode market : marketsNode) {
            markets.add(normalizeMarket(sourceEventId, market));
        }
        return markets;
    }

    private NormalizedMarket normalizeMarket(String sourceEventId, JsonNode market) {
        String sourceMarketId = firstText(market, "id", "conditionId", "slug");
        if (sourceMarketId == null) {
            throw new IllegalArgumentException("Polymarket market is missing id/conditionId/slug");
        }

        String question = firstText(market, "question", "title", "slug");
        IntervalMetadata interval =
                IntervalMetadata.fromText(sourceMarketId + " " + question + " " + text(market, "slug"))
                        .orElse(null);
        List<String> outcomeNames = textArray(market.path("outcomes"));
        List<BigDecimal> outcomePrices = numberArray(market.path("outcomePrices"));
        List<String> tokenIds = textArray(market.path("clobTokenIds"));
        List<NormalizedOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < outcomeNames.size(); i++) {
            String name = outcomeNames.get(i);
            String side = normalizedSide(name);
            outcomes.add(
                    new NormalizedOutcome(
                            side == null ? name : side,
                            name,
                            side,
                            i < tokenIds.size() ? tokenIds.get(i) : null,
                            i,
                            i < outcomePrices.size() ? outcomePrices.get(i) : null));
        }

        BigDecimal lastYes = outcomes.stream()
                .filter(outcome -> "YES".equals(outcome.side()))
                .map(NormalizedOutcome::lastPrice)
                .findFirst()
                .orElse(null);
        BigDecimal lastNo = outcomes.stream()
                .filter(outcome -> "NO".equals(outcome.side()))
                .map(NormalizedOutcome::lastPrice)
                .findFirst()
                .orElse(null);

        return new NormalizedMarket(
                sourceMarketId,
                sourceEventId,
                text(market, "conditionId"),
                text(market, "slug"),
                question,
                text(market, "title"),
                text(market, "description"),
                text(market, "category"),
                tags(market.path("tags")),
                PolymarketStatusNormalizer.normalize(market),
                sourceStatusJson(market),
                lastYes,
                lastNo,
                number(market, "volume", "volumeNum"),
                number(market, "liquidity", "liquidityNum"),
                timestamp(market, "startDate", "start_date"),
                timestamp(market, "endDate", "end_date"),
                timestamp(market, "openTime", "open_time"),
                timestamp(market, "closeTime", "close_time"),
                interval == null ? null : interval.code(),
                interval == null ? null : interval.seconds(),
                text(market, "image", "imageUrl"),
                text(market, "icon", "iconUrl"),
                timestamp(market, "createdAt", "created_at"),
                timestamp(market, "updatedAt", "updated_at"),
                toJson(market),
                outcomes);
    }

    private String requiredTitle(JsonNode event) {
        String title = firstText(event, "title", "question", "slug");
        if (title == null) {
            throw new IllegalArgumentException("Polymarket event is missing title/question/slug");
        }
        return title;
    }

    private List<String> tags(JsonNode tagsNode) {
        if (!tagsNode.isArray()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode tag : tagsNode) {
            String value = tag.isTextual() ? tag.asText() : firstText(tag, "label", "name", "slug");
            if (value != null && !value.isBlank()) {
                tags.add(value);
            }
        }
        return List.copyOf(tags);
    }

    private List<String> textArray(JsonNode node) {
        JsonNode parsed = parseArrayIfText(node);
        if (!parsed.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : parsed) {
            if (!value.isNull()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private List<BigDecimal> numberArray(JsonNode node) {
        JsonNode parsed = parseArrayIfText(node);
        if (!parsed.isArray()) {
            return List.of();
        }
        List<BigDecimal> values = new ArrayList<>();
        for (JsonNode value : parsed) {
            BigDecimal number = number(value);
            if (number != null) {
                values.add(number);
            }
        }
        return values;
    }

    private JsonNode parseArrayIfText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!node.isTextual()) {
            return node;
        }
        try {
            return objectMapper.readTree(node.asText());
        } catch (JsonProcessingException exception) {
            return objectMapper.createArrayNode();
        }
    }

    private String sourceStatusJson(JsonNode node) {
        ObjectNode status = objectMapper.createObjectNode();
        copyIfPresent(node, status, "active");
        copyIfPresent(node, status, "closed");
        copyIfPresent(node, status, "archived");
        return toJson(status);
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode()) {
            target.set(field, value);
        }
    }

    private String normalizedSide(String outcomeName) {
        if (outcomeName == null) {
            return null;
        }
        String normalized = outcomeName.trim().toUpperCase(Locale.ROOT);
        if ("YES".equals(normalized) || "NO".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private BigDecimal number(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = number(node.path(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private OffsetDateTime timestamp(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value == null) {
                continue;
            }
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException ignored) {
                // Keep unmapped source timestamp in raw_payload instead of guessing.
            }
        }
        return null;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize Polymarket payload", exception);
        }
    }
}
