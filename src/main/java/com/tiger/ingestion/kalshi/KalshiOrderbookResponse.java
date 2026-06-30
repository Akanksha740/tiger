package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KalshiOrderbookResponse(@JsonProperty("orderbook_fp") OrderbookFp orderbookFp) {

    private static final BigDecimal ONE = BigDecimal.ONE;

    public boolean hasLiquidity() {
        return orderbookFp != null
                && (!orderbookFp.yesDollars().isEmpty() || !orderbookFp.noDollars().isEmpty());
    }

    public BigDecimal bestYesBid() {
        return bestBid(orderbookFp == null ? List.of() : orderbookFp.yesDollars());
    }

    public BigDecimal bestNoBid() {
        return bestBid(orderbookFp == null ? List.of() : orderbookFp.noDollars());
    }

    public BigDecimal bestYesAsk() {
        BigDecimal bestNoBid = bestNoBid();
        return complement(bestNoBid);
    }

    public BigDecimal bestNoAsk() {
        BigDecimal bestYesBid = bestYesBid();
        return complement(bestYesBid);
    }

    public List<PriceLevel> yesBidLevels() {
        return levels(orderbookFp == null ? List.of() : orderbookFp.yesDollars());
    }

    public List<PriceLevel> noBidLevels() {
        return levels(orderbookFp == null ? List.of() : orderbookFp.noDollars());
    }

    private static BigDecimal bestBid(List<List<String>> sideLevels) {
        if (sideLevels == null || sideLevels.isEmpty()) {
            return null;
        }
        return sideLevels.stream()
                .map(KalshiOrderbookResponse::levelPrice)
                .filter(price -> price != null)
                .max(Comparator.naturalOrder())
                .map(price -> price.setScale(6, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private static BigDecimal complement(BigDecimal bid) {
        if (bid == null) {
            return null;
        }
        return ONE.subtract(bid).setScale(6, RoundingMode.HALF_UP);
    }

    private static List<PriceLevel> levels(List<List<String>> sideLevels) {
        if (sideLevels == null || sideLevels.isEmpty()) {
            return List.of();
        }
        List<PriceLevel> levels = new ArrayList<>();
        for (List<String> level : sideLevels) {
            BigDecimal price = levelPrice(level);
            BigDecimal size = levelSize(level);
            if (price != null && size != null) {
                levels.add(new PriceLevel(price, size));
            }
        }
        levels.sort(Comparator.comparing(PriceLevel::price).reversed());
        return levels;
    }

    private static BigDecimal levelPrice(List<String> level) {
        if (level == null || level.isEmpty()) {
            return null;
        }
        return decimal(level.get(0));
    }

    private static BigDecimal levelSize(List<String> level) {
        if (level == null || level.size() < 2) {
            return null;
        }
        return decimal(level.get(1));
    }

    static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderbookFp(
            @JsonProperty("yes_dollars") List<List<String>> yesDollars,
            @JsonProperty("no_dollars") List<List<String>> noDollars) {

        public OrderbookFp {
            yesDollars = yesDollars == null ? List.of() : yesDollars;
            noDollars = noDollars == null ? List.of() : noDollars;
        }
    }

    public record PriceLevel(BigDecimal price, BigDecimal size) {}
}
