package com.tiger.ingestion.kalshi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiger.domain.CanonicalStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class KalshiNormalizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KalshiNormalizer normalizer = new KalshiNormalizer();

    @Test
    void normalizesSeriesFromKalshiPayload() throws Exception {
        JsonNode payload =
                objectMapper.readTree(
                        """
                        {
                          "series": [
                            {
                              "ticker": "KXTEST",
                              "title": "Test Series",
                              "category": "Sports",
                              "frequency": "daily",
                              "volume_fp": "100.5",
                              "last_updated_ts": 1710000000,
                              "tags": ["nba", "game"]
                            }
                          ]
                        }
                        """);

        NormalizedSeries series = normalizer.normalizeSeriesList(payload).getFirst();

        assertThat(series.sourceSeriesId()).isEqualTo("KXTEST");
        assertThat(series.title()).isEqualTo("Test Series");
        assertThat(series.totalVolume()).isEqualByComparingTo("100.5");
        assertThat(series.tags()).containsExactly("nba", "game");
    }

    @Test
    void normalizesMarketWithYesNoOutcomes() throws Exception {
        JsonNode market =
                objectMapper.readTree(
                        """
                        {
                          "ticker": "MKT-1",
                          "event_ticker": "EVT-1",
                          "series_ticker": "SER-1",
                          "title": "Will it happen?",
                          "status": "open",
                          "last_price_dollars": "0.62",
                          "yes_bid_dollars": "0.61",
                          "yes_ask_dollars": "0.63",
                          "no_bid_dollars": "0.37",
                          "no_ask_dollars": "0.39",
                          "settlement_value_dollars": "1.00"
                        }
                        """);

        NormalizedMarket normalized = normalizer.normalizeMarket(market, null);

        assertThat(normalized.status()).isEqualTo(CanonicalStatus.active);
        assertThat(normalized.lastYesPrice()).isEqualByComparingTo("0.62");
        assertThat(normalized.outcomes()).hasSize(2);
        assertThat(normalized.outcomes().getFirst().outcomeKey()).isEqualTo("yes");
        assertThat(normalized.outcomes().get(1).lastPrice()).isEqualByComparingTo("0.38");
        assertThat(normalized.outcomes().get(1).settlementValue()).isEqualByComparingTo("0.0000");
    }

    @Test
    void mapsOpenStatusToActive() {
        assertThat(KalshiStatusNormalizer.normalize("open")).isEqualTo(CanonicalStatus.active);
        assertThat(KalshiStatusNormalizer.normalize("settled")).isEqualTo(CanonicalStatus.settled);
    }
}
