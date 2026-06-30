package com.tiger.ingestion.kalshi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiger.config.TigerProperties;
import com.tiger.ingestion.kalshi.KalshiOrderbookResponse.OrderbookFp;
import com.tiger.ingestion.kalshi.KalshiOrderbookSnapshotRepository.MarketBookSummary;
import com.tiger.ingestion.kalshi.KalshiOrderbookSnapshotRepository.TrackedMarket;
import com.tiger.ingestion.kalshi.KalshiOrderbookSnapshotRepository.TrackedOutcome;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KalshiOrderbookSnapshotServiceTest {
    @Mock
    private KalshiOrderbookClient orderbookClient;

    @Mock
    private KalshiOrderbookSnapshotRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void snapshotsMarketBooksAndUpdatesLatestPrices() {
        KalshiOrderbookSnapshotService service = newService();
        UUID marketId = UUID.randomUUID();
        UUID yesOutcomeId = UUID.randomUUID();
        UUID noOutcomeId = UUID.randomUUID();
        TrackedMarket market = new TrackedMarket(
                marketId,
                "kalshi:market:KXTEST-24JAN01-T60",
                "KXTEST-24JAN01-T60",
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of(
                        new TrackedOutcome(yesOutcomeId, "YES", "Yes", "YES", 0),
                        new TrackedOutcome(noOutcomeId, "NO", "No", "NO", 1)));
        when(orderbookClient.fetchOrderbook("KXTEST-24JAN01-T60"))
                .thenReturn(Optional.of(new KalshiOrderbookResponse(new OrderbookFp(
                        List.of(List.of("0.4100", "10.00"), List.of("0.4200", "13.00")),
                        List.of(List.of("0.4500", "20.00"), List.of("0.5600", "17.00"))))));

        KalshiOrderbookSnapshotService.SnapshotResult result = service.snapshotMarket(market);

        assertThat(result.inserted()).isTrue();
        verify(repository)
                .updateOutcomePrices(
                        eq(yesOutcomeId),
                        eq(new BigDecimal("0.420000")),
                        eq(new BigDecimal("0.440000")));
        verify(repository)
                .updateOutcomePrices(
                        eq(noOutcomeId),
                        eq(new BigDecimal("0.560000")),
                        eq(new BigDecimal("0.580000")));

        ArgumentCaptor<MarketBookSummary> summaryCaptor = ArgumentCaptor.forClass(MarketBookSummary.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateMarketPrices(eq(marketId), summaryCaptor.capture());
        verify(repository).insertSnapshot(eq(market), any(MarketBookSummary.class), jsonCaptor.capture());

        MarketBookSummary summary = summaryCaptor.getValue();
        assertThat(summary.bookCount()).isEqualTo(2);
        assertThat(summary.bestYesBid()).isEqualByComparingTo("0.420000");
        assertThat(summary.bestYesAsk()).isEqualByComparingTo("0.440000");
        assertThat(jsonCaptor.getValue()).contains("KXTEST-24JAN01-T60", "best_bid", "kalshi_trade_api");
    }

    @Test
    void pollActiveMarkets_shouldSnapshotEveryMarketThreeTimesInParallel() {
        KalshiOrderbookSnapshotService service = newService();
        UUID marketId = UUID.randomUUID();
        TrackedMarket market = new TrackedMarket(
                marketId,
                "kalshi:market:KXTEST-24JAN01-T60",
                "KXTEST-24JAN01-T60",
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of(new TrackedOutcome(UUID.randomUUID(), "YES", "Yes", "YES", 0)));
        when(repository.findActiveMarkets(0)).thenReturn(List.of(market));
        when(orderbookClient.fetchOrderbook("KXTEST-24JAN01-T60"))
                .thenReturn(Optional.of(new KalshiOrderbookResponse(new OrderbookFp(
                        List.of(List.of("0.4200", "13.00")), List.of(List.of("0.5600", "17.00"))))));

        KalshiOrderbookSnapshotService.SnapshotPollResult result = service.pollActiveMarkets(0, 3, 0, 4);

        assertThat(result.marketsScanned()).isEqualTo(1);
        assertThat(result.samplesTaken()).isEqualTo(3);
        assertThat(result.snapshotsInserted()).isEqualTo(3);
        assertThat(result.parallelism()).isEqualTo(4);
        verify(repository, times(3)).insertSnapshot(eq(market), any(MarketBookSummary.class), any(String.class));
    }

    private KalshiOrderbookSnapshotService newService() {
        TigerProperties properties =
                new TigerProperties(
                        new TigerProperties.Polymarket(
                                "https://gamma-api.polymarket.com", "https://clob.polymarket.com", 100),
                        new TigerProperties.Kalshi("demo", "key", "secrets/kalshi_private.key", "/trade-api/v2", 3),
                        new TigerProperties.Ingestion(
                                new TigerProperties.PolymarketEvents(false, 100, 0),
                                new TigerProperties.PolymarketCatalog(false, 100, 0, 0),
                                new TigerProperties.PolymarketOrderbookSnapshots(false, false, 100, 60_000),
                                new TigerProperties.KalshiOrderbookSnapshots(false, false, 0, 60_000, 3, 1_000, 16),
                                new TigerProperties.KalshiSeries(false, null),
                                new TigerProperties.KalshiEvents(false, 50, true, false, null),
                                new TigerProperties.KalshiOpenMarkets(false, 200),
                                new TigerProperties.KalshiCatalog(false, false, false),
                                false));
        return new KalshiOrderbookSnapshotService(orderbookClient, repository, objectMapper, properties);
    }
}
