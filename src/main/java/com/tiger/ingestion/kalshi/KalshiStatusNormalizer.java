package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiger.domain.CanonicalStatus;
import java.util.Locale;

final class KalshiStatusNormalizer {
    private KalshiStatusNormalizer() {}

    static CanonicalStatus normalize(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return CanonicalStatus.unknown;
        }
        return switch (rawStatus.toLowerCase(Locale.ROOT)) {
            case "unopened" -> CanonicalStatus.unopened;
            case "open" -> CanonicalStatus.active;
            case "paused" -> CanonicalStatus.paused;
            case "closed" -> CanonicalStatus.closed;
            case "settled" -> CanonicalStatus.settled;
            default -> CanonicalStatus.unknown;
        };
    }

    static CanonicalStatus normalize(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return normalize(value.asText());
            }
        }
        return CanonicalStatus.unknown;
    }

    static String sourceStatusJson(JsonNode node, String field) {
        ObjectNode status = JsonNodeFactory.instance.objectNode();
        JsonNode value = node.get(field);
        if (value != null && !value.isNull()) {
            status.put("status", value.asText());
        }
        return status.toString();
    }
}
