package com.tiger.ingestion.polymarket;

import java.math.BigDecimal;

public record NormalizedOutcome(
        String outcomeKey,
        String outcomeName,
        String side,
        String tokenId,
        int position,
        BigDecimal lastPrice) {}
