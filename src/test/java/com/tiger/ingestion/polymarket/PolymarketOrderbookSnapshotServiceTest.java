package com.tiger.ingestion.polymarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotRepository.MarketBookSummary;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotRepository.TrackedMarket;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotRepository.TrackedOutcome;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolymarketOrderbookSnapshotServiceTest {
    @Mock
    private PolymarketClobClient clobClient;

    @Mock
    private PolymarketOrderbookSnapshotRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void snapshotsMarketBooksAndUpdatesLatestPrices() {
        PolymarketOrderbookSnapshotService service =
                new PolymarketOrderbookSnapshotService(clobClient, repository, objectMapper);
        UUID marketId = UUID.randomUUID();
        UUID yesOutcomeId = UUID.randomUUID();
        UUID noOutcomeId = UUID.randomUUID();
        TrackedMarket market = new TrackedMarket(
                marketId,
                "polymarket:market:btc-15m",
                "btc-15m",
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of(
                        new TrackedOutcome(yesOutcomeId, "YES", "Yes", "YES", "yes-token", 0),
                        new TrackedOutcome(noOutcomeId, "NO", "No", "NO", "no-token", 1)));
        when(clobClient.fetchOrderbooks(List.of("yes-token", "no-token")))
                .thenReturn(List.of(book("yes-token", "0.61", "0.64", "0.62"), book("no-token", "0.36", "0.39", "0.38")));

        PolymarketOrderbookSnapshotService.SnapshotResult result = service.snapshotMarket(market);

        assertThat(result.inserted()).isTrue();
        verify(repository)
                .updateOutcomePrices(
                        eq(yesOutcomeId),
                        eq(new BigDecimal("0.610000")),
                        eq(new BigDecimal("0.640000")),
                        eq(new BigDecimal("0.620000")));
        verify(repository)
                .updateOutcomePrices(
                        eq(noOutcomeId),
                        eq(new BigDecimal("0.360000")),
                        eq(new BigDecimal("0.390000")),
                        eq(new BigDecimal("0.380000")));

        ArgumentCaptor<MarketBookSummary> summaryCaptor = ArgumentCaptor.forClass(MarketBookSummary.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateMarketPrices(eq(marketId), summaryCaptor.capture());
        verify(repository).insertSnapshot(eq(market), any(MarketBookSummary.class), jsonCaptor.capture());

        MarketBookSummary summary = summaryCaptor.getValue();
        assertThat(summary.bookCount()).isEqualTo(2);
        assertThat(summary.bestYesBid()).isEqualByComparingTo("0.610000");
        assertThat(summary.bestNoAsk()).isEqualByComparingTo("0.390000");
        assertThat(jsonCaptor.getValue()).contains("yes-token", "no-token", "best_bid", "asks");
    }

    private PolymarketOrderbookResponse book(String tokenId, String bid, String ask, String last) {
        return new PolymarketOrderbookResponse(
                "0xcondition",
                tokenId,
                "1710000000000",
                last,
                List.of(new PolymarketOrderbookResponse.PriceLevel(bid, "10")),
                List.of(new PolymarketOrderbookResponse.PriceLevel(ask, "20")));
    }
}
