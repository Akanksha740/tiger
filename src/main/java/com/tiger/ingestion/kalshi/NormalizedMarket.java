package com.tiger.ingestion.kalshi;

import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record NormalizedMarket(
        String sourceMarketId,
        String sourceMarketTicker,
        String sourceEventId,
        String sourceSeriesId,
        String question,
        String title,
        String subtitle,
        String rulesPrimary,
        String rulesSecondary,
        String category,
        List<String> tags,
        CanonicalStatus status,
        String sourceStatusJson,
        BigDecimal lastYesPrice,
        BigDecimal lastNoPrice,
        BigDecimal bestYesBid,
        BigDecimal bestYesAsk,
        BigDecimal bestNoBid,
        BigDecimal bestNoAsk,
        BigDecimal volume,
        BigDecimal volume24h,
        BigDecimal openInterest,
        BigDecimal liquidity,
        BigDecimal settlementValue,
        OffsetDateTime openAt,
        OffsetDateTime closeAt,
        OffsetDateTime settledAt,
        OffsetDateTime sourceUpdatedAt,
        List<NormalizedOutcome> outcomes) {}
