package com.tiger.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SearchParameters(
        String q,
        String exchange,
        String category,
        String status,
        String seriesId,
        BigDecimal minVolume,
        BigDecimal minLiquidity,
        OffsetDateTime discoveredAfter,
        OffsetDateTime settledAfter,
        OffsetDateTime settledBefore,
        int limit,
        int offset) {
    public SearchParameters {
        limit = validateLimit(limit);
        offset = validateOffset(offset);
        q = blankToNull(q);
        exchange = blankToNull(exchange);
        category = blankToNull(category);
        status = blankToNull(status);
        seriesId = blankToNull(seriesId);
    }

    public static int validateLimit(Integer limit) {
        int value = limit == null ? 25 : limit;
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return value;
    }

    public static int validateOffset(Integer offset) {
        int value = offset == null ? 0 : offset;
        if (value < 0) {
            throw new IllegalArgumentException("offset must be zero or greater");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
