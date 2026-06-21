package com.tiger.ingestion.kalshi;

import java.math.BigDecimal;

public record NormalizedOutcome(
        String outcomeKey,
        String outcomeName,
        String side,
        int position,
        BigDecimal lastPrice,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal settlementValue,
        String rawPayloadJson) {}
