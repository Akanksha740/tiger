package com.tiger.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MarketResponse(
        String marketId,
        String exchange,
        String sourceMarketId,
        String sourceEventId,
        String sourceSeriesId,
        String question,
        String category,
        String status,
        BigDecimal lastYesPrice,
        BigDecimal lastNoPrice,
        BigDecimal volume,
        BigDecimal liquidity,
        BigDecimal openInterest,
        OffsetDateTime discoveredAt,
        OffsetDateTime settledAt) {}
