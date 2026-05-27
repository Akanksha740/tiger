package com.tiger.ingestion.polymarket;

import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record NormalizedMarket(
        String sourceMarketId,
        String sourceEventId,
        String conditionId,
        String slug,
        String question,
        String title,
        String description,
        String category,
        List<String> tags,
        CanonicalStatus status,
        String sourceStatusJson,
        BigDecimal lastYesPrice,
        BigDecimal lastNoPrice,
        BigDecimal volume,
        BigDecimal liquidity,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime openAt,
        OffsetDateTime closeAt,
        String intervalCode,
        Integer intervalSeconds,
        String imageUrl,
        String iconUrl,
        OffsetDateTime sourceCreatedAt,
        OffsetDateTime sourceUpdatedAt,
        String rawPayloadJson,
        List<NormalizedOutcome> outcomes) {}
