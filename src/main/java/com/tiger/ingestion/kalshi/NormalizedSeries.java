package com.tiger.ingestion.kalshi;

import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record NormalizedSeries(
        String sourceSeriesId,
        String sourceTicker,
        String title,
        String subtitle,
        String category,
        List<String> tags,
        String frequency,
        CanonicalStatus status,
        String sourceStatusJson,
        BigDecimal totalVolume,
        OffsetDateTime sourceUpdatedAt) {}
