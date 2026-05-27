package com.tiger.ingestion.polymarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiger.domain.CanonicalStatus;
import org.junit.jupiter.api.Test;

class PolymarketNormalizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PolymarketNormalizer normalizer = new PolymarketNormalizer(objectMapper);

    @Test
    void normalizesEventWithNestedBinaryMarket() throws Exception {
        JsonNode payload =
                objectMapper.readTree(
                        """
                        {
                          "id": "btc-event",
                          "slug": "btc-up-or-down-15m",
                          "title": "Bitcoin Up or Down - 15m",
                          "category": "Crypto",
                          "active": true,
                          "closed": false,
                          "archived": false,
                          "volume": "123.45",
                          "liquidity": "67.89",
                          "tags": [{"label": "Bitcoin"}, {"slug": "crypto"}],
                          "markets": [
                            {
                              "id": "btc-market",
                              "conditionId": "0xabc",
                              "question": "Will Bitcoin be up in 15 minutes?",
                              "active": true,
                              "closed": false,
                              "archived": false,
                              "outcomes": "[\\"Yes\\", \\"No\\"]",
                              "outcomePrices": "[\\"0.62\\", \\"0.38\\"]",
                              "clobTokenIds": "[\\"yes-token\\", \\"no-token\\"]",
                              "volumeNum": 100,
                              "liquidityNum": 50
                            }
                          ]
                        }
                        """);

        NormalizedEvent event = normalizer.normalizeEvent(payload);

        assertThat(event.sourceEventId()).isEqualTo("btc-event");
        assertThat(event.status()).isEqualTo(CanonicalStatus.active);
        assertThat(event.tags()).containsExactly("Bitcoin", "crypto");
        assertThat(event.marketCount()).isEqualTo(1);
        assertThat(event.marketQuestions()).containsExactly("Will Bitcoin be up in 15 minutes?");

        NormalizedMarket market = event.markets().getFirst();
        assertThat(market.sourceMarketId()).isEqualTo("btc-market");
        assertThat(market.intervalCode()).isEqualTo("15m");
        assertThat(market.intervalSeconds()).isEqualTo(900);
        assertThat(market.lastYesPrice()).isEqualByComparingTo("0.62");
        assertThat(market.lastNoPrice()).isEqualByComparingTo("0.38");
        assertThat(market.outcomes()).hasSize(2);
        assertThat(market.outcomes().getFirst().tokenId()).isEqualTo("yes-token");
    }

    @Test
    void archivesWinOverActiveWhenNormalizingStatus() throws Exception {
        JsonNode payload =
                objectMapper.readTree(
                        """
                        {
                          "id": "old-event",
                          "title": "Old event",
                          "active": true,
                          "closed": false,
                          "archived": true,
                          "markets": []
                        }
                        """);

        NormalizedEvent event = normalizer.normalizeEvent(payload);

        assertThat(event.status()).isEqualTo(CanonicalStatus.archived);
    }
}
