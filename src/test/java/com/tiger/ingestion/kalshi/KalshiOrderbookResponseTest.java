package com.tiger.ingestion.kalshi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KalshiOrderbookResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsKalshiOrderbookAndDerivesImpliedAsks() throws Exception {
        KalshiOrderbookResponse book = objectMapper.readValue(
                """
                {
                  "orderbook_fp": {
                    "yes_dollars": [
                      ["0.4100", "10.00"],
                      ["0.4200", "13.00"]
                    ],
                    "no_dollars": [
                      ["0.4500", "20.00"],
                      ["0.5600", "17.00"]
                    ]
                  }
                }
                """,
                KalshiOrderbookResponse.class);

        assertThat(book.bestYesBid()).isEqualByComparingTo("0.420000");
        assertThat(book.bestNoBid()).isEqualByComparingTo("0.560000");
        assertThat(book.bestYesAsk()).isEqualByComparingTo("0.440000");
        assertThat(book.bestNoAsk()).isEqualByComparingTo("0.580000");
        assertThat(book.yesBidLevels()).hasSize(2);
        assertThat(book.yesBidLevels().get(0).price()).isEqualByComparingTo("0.4200");
    }
}
