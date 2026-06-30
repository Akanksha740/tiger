package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KalshiNormalizer {

    public List<NormalizedSeries> normalizeSeriesList(JsonNode response) {
        JsonNode series = response.path("series");
        if (!series.isArray()) {
            return List.of();
        }
        List<NormalizedSeries> normalized = new ArrayList<>();
        for (JsonNode item : series) {
            normalized.add(normalizeSeries(item));
        }
        return normalized;
    }

    public NormalizedSeries normalizeSeries(JsonNode series) {
        String ticker = requiredText(series, "ticker");
        return new NormalizedSeries(
                ticker,
                ticker,
                defaultTitle(series, ticker),
                text(series, "sub_title", "subtitle"),
                text(series, "category"),
                stringTags(series.path("tags")),
                text(series, "frequency"),
                KalshiStatusNormalizer.normalize(series, "status"),
                KalshiStatusNormalizer.sourceStatusJson(series, "status"),
                number(series, "volume_fp", "volume"),
                sourceTimestamp(series, "last_updated_ts"));
    }

    public List<NormalizedEvent> normalizeEvents(List<JsonNode> events) {
        List<NormalizedEvent> normalized = new ArrayList<>();
        for (JsonNode event : events) {
            normalized.add(normalizeEvent(event));
        }
        return normalized;
    }

    public NormalizedEvent normalizeEvent(JsonNode event) {
        String eventTicker = requiredText(event, "event_ticker");
        List<NormalizedMarket> markets = normalizeNestedMarkets(event);
        List<String> marketQuestions = markets.stream().map(NormalizedMarket::question).toList();
        int activeMarkets =
                (int) markets.stream().filter(m -> m.status() == CanonicalStatus.active).count();
        OffsetDateTime strikeAt = sourceTimestamp(event, "strike_date");

        return new NormalizedEvent(
                eventTicker,
                eventTicker,
                text(event, "series_ticker"),
                defaultTitle(event, eventTicker),
                text(event, "sub_title", "subtitle"),
                text(event, "category"),
                stringTags(event.path("tags")),
                KalshiStatusNormalizer.normalize(event, "status"),
                KalshiStatusNormalizer.sourceStatusJson(event, "status"),
                strikeAt,
                strikeAt,
                strikeAt,
                text(event, "strike_period"),
                markets.size(),
                activeMarkets,
                marketQuestions,
                number(event, "volume_fp", "volume"),
                sourceTimestamp(event, "last_updated_ts"),
                markets);
    }

    public List<NormalizedMarket> normalizeMarkets(List<JsonNode> markets) {
        List<NormalizedMarket> normalized = new ArrayList<>();
        for (JsonNode market : markets) {
            normalized.add(normalizeMarket(market, null));
        }
        return normalized;
    }

    public NormalizedMarket normalizeMarket(JsonNode market, JsonNode parentEvent) {
        String ticker = requiredText(market, "ticker");
        String eventTicker =
                firstText(market, "event_ticker") != null
                        ? text(market, "event_ticker")
                        : parentEvent == null ? null : text(parentEvent, "event_ticker");
        if (eventTicker == null) {
            throw new IllegalArgumentException("Kalshi market is missing event_ticker: " + ticker);
        }
        String seriesTicker =
                text(market, "series_ticker") != null
                        ? text(market, "series_ticker")
                        : parentEvent == null ? null : text(parentEvent, "series_ticker");
        BigDecimal yesLast = number(market, "last_price_dollars");
        return new NormalizedMarket(
                ticker,
                ticker,
                eventTicker,
                seriesTicker,
                defaultTitle(market, ticker),
                text(market, "title"),
                text(market, "subtitle", "sub_title"),
                combineRules(market),
                text(market, "category"),
                stringTags(market.path("tags")),
                KalshiStatusNormalizer.normalize(market, "status"),
                KalshiStatusNormalizer.sourceStatusJson(market, "status"),
                yesLast,
                oneMinus(yesLast),
                number(market, "yes_bid_dollars"),
                number(market, "yes_ask_dollars"),
                number(market, "no_bid_dollars"),
                number(market, "no_ask_dollars"),
                number(market, "volume_fp", "volume"),
                number(market, "volume_24h_fp"),
                number(market, "open_interest_fp"),
                number(market, "liquidity_dollars"),
                number(market, "settlement_value_dollars"),
                sourceTimestamp(market, "open_time"),
                sourceTimestamp(market, "close_time"),
                sourceTimestamp(market, "settlement_ts"),
                sourceTimestamp(market, "last_updated_ts"),
                buildOutcomes(market));
    }

    private String combineRules(JsonNode market) {
        String primary = text(market, "rules_primary");
        String secondary = text(market, "rules_secondary");
        if (primary == null) {
            return secondary;
        }
        if (secondary == null) {
            return primary;
        }
        return primary + "\n\n" + secondary;
    }

    private List<NormalizedMarket> normalizeNestedMarkets(JsonNode event) {
        JsonNode markets = event.path("markets");
        if (!markets.isArray()) {
            return List.of();
        }
        List<NormalizedMarket> normalized = new ArrayList<>();
        for (JsonNode market : markets) {
            normalized.add(normalizeMarket(market, event));
        }
        return normalized;
    }

    private List<NormalizedOutcome> buildOutcomes(JsonNode market) {
        BigDecimal yesLast = number(market, "last_price_dollars");
        BigDecimal settlement = number(market, "settlement_value_dollars");
        String yesName = firstText(market, "yes_sub_title");
        if (yesName == null) {
            yesName = "YES";
        }
        String noName = firstText(market, "no_sub_title");
        if (noName == null) {
            noName = "NO";
        }

        return List.of(
                new NormalizedOutcome(
                        "yes",
                        yesName,
                        "yes",
                        0,
                        yesLast,
                        number(market, "yes_bid_dollars"),
                        number(market, "yes_ask_dollars"),
                        settlement),
                new NormalizedOutcome(
                        "no",
                        noName,
                        "no",
                        1,
                        oneMinus(yesLast),
                        number(market, "no_bid_dollars"),
                        number(market, "no_ask_dollars"),
                        oneMinus(settlement)));
    }

    private List<String> stringTags(JsonNode tagsNode) {
        if (!tagsNode.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : tagsNode) {
            if (tag.isTextual()) {
                tags.add(tag.asText());
            } else {
                String label = text(tag, "label", "name", "slug");
                if (label != null) {
                    tags.add(label);
                }
            }
        }
        return tags;
    }

    private BigDecimal oneMinus(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP).subtract(value);
    }

    private String defaultTitle(JsonNode node, String fallback) {
        String title = text(node, "title");
        return title != null ? title : fallback;
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Kalshi payload missing required field: " + field);
        }
        return value;
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
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private OffsetDateTime sourceTimestamp(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return OffsetDateTime.ofInstant(Instant.ofEpochSecond(value.asLong()), ZoneOffset.UTC);
            }
            String text = value.asText();
            if (text.isBlank()) {
                continue;
            }
            try {
                return OffsetDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(text)), ZoneOffset.UTC);
                } catch (NumberFormatException ignoredAgain) {
                    // Skip unparseable timestamps.
                }
            }
        }
        return null;
    }
}
