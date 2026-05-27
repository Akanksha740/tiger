package com.tiger.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record EventResponse(
        String eventId,
        String exchange,
        String sourceEventId,
        String sourceSeriesId,
        String title,
        String category,
        String status,
        Integer marketCount,
        BigDecimal totalVolume,
        BigDecimal totalLiquidity,
        List<String> marketQuestions,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime discoveredAt) {}
