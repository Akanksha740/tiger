package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolymarketOrderbookResponse(
        String market,
        @JsonProperty("asset_id") String assetId,
        String timestamp,
        @JsonProperty("last_trade_price") String lastTradePrice,
        List<PriceLevel> bids,
        List<PriceLevel> asks) {

    public BigDecimal bestBid() {
        return bestPrice(bids, Comparator.naturalOrder());
    }

    public BigDecimal bestAsk() {
        return bestPrice(asks, Comparator.reverseOrder());
    }

    public BigDecimal lastTradePriceDecimal() {
        return decimal(lastTradePrice);
    }

    private BigDecimal bestPrice(List<PriceLevel> levels, Comparator<BigDecimal> comparator) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        return levels.stream()
                .map(PriceLevel::priceDecimal)
                .filter(price -> price != null)
                .max(comparator)
                .orElse(null);
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
    public record PriceLevel(String price, String size) {
        public BigDecimal priceDecimal() {
            return decimal(price);
        }

        public BigDecimal sizeDecimal() {
            return decimal(size);
        }
    }
}
