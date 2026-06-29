package com.tiger.ingestion.kalshi;

import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record NormalizedEvent(
        String sourceEventId,
        String sourceEventTicker,
        String sourceSeriesId,
        String title,
        String subtitle,
        String category,
        List<String> tags,
        CanonicalStatus status,
        String sourceStatusJson,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime strikeAt,
        String strikePeriod,
        int marketCount,
        int activeMarketCount,
        List<String> marketQuestions,
        BigDecimal totalVolume,
        OffsetDateTime sourceUpdatedAt,
        List<NormalizedMarket> markets) {}
