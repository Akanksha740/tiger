package com.tiger.ingestion.polymarket;

import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record NormalizedEvent(
        String sourceEventId,
        String sourceEventTicker,
        String slug,
        String sourceSeriesId,
        String title,
        String description,
        String category,
        List<String> tags,
        CanonicalStatus status,
        String sourceStatusJson,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        int marketCount,
        int activeMarketCount,
        List<String> marketQuestions,
        BigDecimal totalVolume,
        BigDecimal totalLiquidity,
        BigDecimal openInterest,
        String imageUrl,
        String iconUrl,
        OffsetDateTime sourceCreatedAt,
        OffsetDateTime sourceUpdatedAt,
        List<NormalizedMarket> markets) {}
