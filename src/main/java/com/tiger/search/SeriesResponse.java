package com.tiger.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SeriesResponse(
        String seriesId,
        String exchange,
        String sourceSeriesId,
        String title,
        String category,
        String status,
        String intervalCode,
        Integer nEventsTotal,
        Integer nEventsActive,
        BigDecimal totalVolume,
        BigDecimal totalLiquidity,
        OffsetDateTime earliestEventAt,
        OffsetDateTime latestEventAt,
        OffsetDateTime discoveredAt) {}
