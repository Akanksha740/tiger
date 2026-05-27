package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.domain.CanonicalStatus;

public final class PolymarketStatusNormalizer {
    private PolymarketStatusNormalizer() {}

    public static CanonicalStatus normalize(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return CanonicalStatus.unknown;
        }
        if (raw.path("archived").asBoolean(false)) {
            return CanonicalStatus.archived;
        }
        if (raw.path("closed").asBoolean(false)) {
            return CanonicalStatus.closed;
        }
        if (raw.path("active").asBoolean(false)) {
            return CanonicalStatus.active;
        }
        return CanonicalStatus.unknown;
    }
}
