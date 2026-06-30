package com.tiger.ingestion.polymarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PolymarketOrderbookResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsClobBookAndFindsBestPrices() throws Exception {
        PolymarketOrderbookResponse book = objectMapper.readValue(
                """
                {
                  "market": "0xcondition",
                  "asset_id": "yes-token",
                  "timestamp": "1710000000000",
                  "last_trade_price": "0.62",
                  "bids": [{"price": "0.59", "size": "12"}, {"price": "0.61", "size": "5"}],
                  "asks": [{"price": "0.65", "size": "7"}, {"price": "0.64", "size": "9"}]
                }
                """,
                PolymarketOrderbookResponse.class);

        assertThat(book.assetId()).isEqualTo("yes-token");
        assertThat(book.bestBid()).isEqualByComparingTo("0.61");
        assertThat(book.bestAsk()).isEqualByComparingTo("0.64");
        assertThat(book.lastTradePriceDecimal()).isEqualByComparingTo("0.62");
    }
}
