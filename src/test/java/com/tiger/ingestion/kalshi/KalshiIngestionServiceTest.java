package com.tiger.ingestion.kalshi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tiger.config.TigerProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KalshiIngestionServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private KalshiApiClient apiClient;

    @Mock
    private KalshiNormalizer normalizer;

    @Mock
    private KalshiIngestionRepository repository;

    @Mock
    private KalshiIngestionStateRepository stateRepository;

    private KalshiIngestionService service;

    @BeforeEach
    void setUp() {
        TigerProperties properties =
                new TigerProperties(
                        new TigerProperties.Polymarket(
                                "https://gamma-api.polymarket.com", "https://clob.polymarket.com", 100),
                        new TigerProperties.Kalshi(
                                "demo", "key", "secrets/kalshi_private.key", "/trade-api/v2", 3, 4, 20_000, 30_000, 3),
                        new TigerProperties.Ingestion(
                                new TigerProperties.PolymarketEvents(false, 100, 0),
                                new TigerProperties.PolymarketCatalog(false, 100, 0, 0),
                                new TigerProperties.PolymarketOrderbookSnapshots(false, false, 100, 60_000),
                                new TigerProperties.KalshiOrderbookSnapshots(false, false, 0, 60_000, 3, 1_000, 2),
                                new TigerProperties.KalshiSeries(false, null),
                                new TigerProperties.KalshiEvents(false, 50, true, false, null),
                                new TigerProperties.KalshiOpenMarkets(false, 200),
                                new TigerProperties.KalshiCatalog(false, false, false),
                                false));
        service =
                new KalshiIngestionService(
                        apiClient, normalizer, repository, stateRepository, properties);
    }

    @Test
    void resolveEventsMinUpdatedTs_shouldApplyOverlapFromIngestionState() {
        when(stateRepository.lastSourceUpdatedAt("events"))
                .thenReturn(Optional.of(OffsetDateTime.of(2026, 6, 1, 12, 2, 0, 0, ZoneOffset.UTC)));

        Long resolved = service.resolveEventsMinUpdatedTs(null);

        assertThat(resolved).isEqualTo(
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC).toEpochSecond());
    }

    @Test
    void ingestEventsIncremental_shouldBootstrapNewSeriesThenPollEventDelta() throws Exception {
        when(stateRepository.startRun("events")).thenReturn(UUID.randomUUID());
        stubEmptyEventsPages();

        service.ingestEventsIncremental(List.of("KXNEW", "KXNEW"), 1_700_000_000L, true);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(apiClient, org.mockito.Mockito.times(2)).forEachPage(eq("/events"), eq("events"), paramsCaptor.capture(), any());

        List<Map<String, Object>> passes = paramsCaptor.getAllValues();
        assertThat(passes.get(0))
                .containsEntry("series_ticker", "KXNEW")
                .containsEntry("with_nested_markets", "true")
                .containsEntry("include_volume", "true")
                .doesNotContainKey("min_updated_ts");
        assertThat(passes.get(1))
                .containsEntry("min_updated_ts", 1_700_000_000L)
                .doesNotContainKey("series_ticker");
    }

    @Test
    void ingestSeries_shouldTrackInsertedSeriesTickers() throws Exception {
        when(stateRepository.startRun("series")).thenReturn(UUID.randomUUID());
        NormalizedSeries existing =
                new NormalizedSeries(
                        "OLD",
                        "OLD",
                        "Old series",
                        null,
                        null,
                        List.of(),
                        null,
                        com.tiger.domain.CanonicalStatus.active,
                        "{}",
                        null,
                        null);
        NormalizedSeries inserted =
                new NormalizedSeries(
                        "KXNEW",
                        "KXNEW",
                        "New series",
                        null,
                        null,
                        List.of("crypto"),
                        null,
                        com.tiger.domain.CanonicalStatus.active,
                        "{}",
                        null,
                        null);
        when(apiClient.get(eq("/series"), any())).thenReturn(MAPPER.createObjectNode());
        when(normalizer.normalizeSeriesList(any())).thenReturn(List.of(existing, inserted));
        when(repository.upsertSeries(existing)).thenReturn(KalshiIngestionRepository.UpsertResult.updated());
        when(repository.upsertSeries(inserted)).thenReturn(KalshiIngestionRepository.UpsertResult.INSERTED);

        KalshiIngestionService.SeriesIngestionResult result = service.ingestSeries(null);

        assertThat(result.insertedSeriesTickers()).containsExactly("KXNEW");
        assertThat(result.counters().inserted).isEqualTo(1);
        assertThat(result.counters().updated).isEqualTo(1);
    }

    private void stubEmptyEventsPages() {
        doAnswer(
                        invocation -> {
                            KalshiPageConsumer consumer = invocation.getArgument(3);
                            ArrayNode empty = MAPPER.createArrayNode();
                            consumer.accept(empty, 0, null);
                            return null;
                        })
                .when(apiClient)
                .forEachPage(eq("/events"), eq("events"), any(), any());
    }
}
