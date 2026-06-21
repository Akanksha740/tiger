package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(KalshiApiClient.class)
public class KalshiIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KalshiIngestionService.class);
    private static final String ENTITY_SERIES = "series";
    private static final String ENTITY_EVENTS = "events";
    private static final String ENTITY_MARKETS = "markets";
    private static final int OVERLAP_SECONDS = 120;

    private final KalshiApiClient apiClient;
    private final KalshiNormalizer normalizer;
    private final KalshiIngestionRepository repository;
    private final KalshiIngestionStateRepository stateRepository;
    private final TigerProperties properties;

    public KalshiIngestionService(
            KalshiApiClient apiClient,
            KalshiNormalizer normalizer,
            KalshiIngestionRepository repository,
            KalshiIngestionStateRepository stateRepository,
            TigerProperties properties) {
        this.apiClient = apiClient;
        this.normalizer = normalizer;
        this.repository = repository;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    public IngestionCounters ingestSeries(Long minUpdatedTs) {
        stateRepository.markAttempt(ENTITY_SERIES, "/series");
        UUID runId = stateRepository.startRun(ENTITY_SERIES);
        IngestionCounters counters = new IngestionCounters();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("include_product_metadata", "true");
            params.put("include_volume", "true");
            if (minUpdatedTs != null) {
                params.put("min_updated_ts", minUpdatedTs);
            }
            JsonNode payload = apiClient.get("/series", params);
            List<NormalizedSeries> series = normalizer.normalizeSeriesList(payload);
            for (NormalizedSeries item : series) {
                counters.fetched++;
                try {
                    if (repository.upsertSeries(item) == KalshiIngestionRepository.UpsertResult.INSERTED) {
                        counters.inserted++;
                    } else {
                        counters.updated++;
                    }
                } catch (RuntimeException ex) {
                    counters.failed++;
                    log.warn("Failed Kalshi series upsert for {}", item.sourceSeriesId(), ex);
                }
            }
            stateRepository.markSuccess(ENTITY_SERIES, OffsetDateTime.now(ZoneOffset.UTC), null);
            stateRepository.finishRun(
                    runId, "success", counters.fetched, counters.inserted, counters.updated, counters.failed, null, null);
            log.info(
                    "Kalshi series ingestion complete: fetched={} inserted={} updated={} failed={}",
                    counters.fetched,
                    counters.inserted,
                    counters.updated,
                    counters.failed);
        } catch (RuntimeException ex) {
            stateRepository.markFailure(ENTITY_SERIES, ex.getMessage());
            stateRepository.finishRun(
                    runId, "failed", counters.fetched, counters.inserted, counters.updated, counters.failed, null, ex.getMessage());
            throw ex;
        }
        return counters;
    }

    public IngestionCounters ingestEvents(boolean withNestedMarkets) {
        stateRepository.markAttempt(ENTITY_EVENTS, "/events");
        UUID runId = stateRepository.startRun(ENTITY_EVENTS);
        IngestionCounters counters = new IngestionCounters();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("limit", properties.ingestion().kalshiEvents().pageLimit());
            params.put("with_nested_markets", String.valueOf(withNestedMarkets).toLowerCase());
            apiClient.forEachPage(
                    "/events",
                    "events",
                    params,
                    (page, pageIndex, cursorAfter) -> {
                        if (!page.isArray()) {
                            return;
                        }
                        int pageEvents = 0;
                        for (JsonNode rawEvent : page) {
                            counters.fetched++;
                            pageEvents++;
                            try {
                                NormalizedEvent event = normalizer.normalizeEvent(rawEvent);
                                repository.upsertEventTree(event);
                                counters.events++;
                                counters.markets += event.markets().size();
                                counters.outcomes += event.markets().size() * 2L;
                            } catch (RuntimeException ex) {
                                counters.failed++;
                                log.warn(
                                        "Failed Kalshi event tree upsert for {}",
                                        rawEvent.path("event_ticker").asText("unknown"),
                                        ex);
                            }
                        }
                        log.info(
                                "Kalshi events page {} complete: pageEvents={} totalEvents={} cursor={}",
                                pageIndex,
                                pageEvents,
                                counters.events,
                                cursorAfter == null ? "done" : "more");
                    });
            counters.inserted = counters.events;
            counters.updated = 0;
            stateRepository.markSuccess(ENTITY_EVENTS, OffsetDateTime.now(ZoneOffset.UTC), null);
            stateRepository.finishRun(
                    runId, "success", counters.fetched, counters.inserted, counters.updated, counters.failed, null, null);
            log.info(
                    "Kalshi events ingestion complete: events={} markets={} outcomes~={} failed={}",
                    counters.events,
                    counters.markets,
                    counters.outcomes,
                    counters.failed);
        } catch (RuntimeException ex) {
            stateRepository.markFailure(ENTITY_EVENTS, ex.getMessage());
            stateRepository.finishRun(
                    runId, "failed", counters.fetched, counters.inserted, counters.updated, counters.failed, null, ex.getMessage());
            throw ex;
        }
        return counters;
    }

    public IngestionCounters ingestOpenMarkets() {
        stateRepository.markAttempt(ENTITY_MARKETS, "/markets");
        UUID runId = stateRepository.startRun(ENTITY_MARKETS);
        IngestionCounters counters = new IngestionCounters();
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("limit", properties.ingestion().kalshiOpenMarkets().pageLimit());
            params.put("status", "open");
            params.put("mve_filter", "exclude");
            apiClient.forEachPage(
                    "/markets",
                    "markets",
                    params,
                    (page, pageIndex, cursorAfter) -> {
                        if (!page.isArray()) {
                            return;
                        }
                        int pageMarkets = 0;
                        for (JsonNode rawMarket : page) {
                            counters.fetched++;
                            pageMarkets++;
                            try {
                                NormalizedMarket market = normalizer.normalizeMarket(rawMarket, null);
                                repository.upsertMarketBundle(market);
                                counters.markets++;
                                counters.outcomes += 2;
                            } catch (RuntimeException ex) {
                                counters.failed++;
                                log.warn(
                                        "Failed Kalshi market upsert for {}",
                                        rawMarket.path("ticker").asText(),
                                        ex);
                            }
                        }
                        log.info(
                                "Kalshi open markets page {} complete: pageMarkets={} totalMarkets={}",
                                pageIndex,
                                pageMarkets,
                                counters.markets);
                    });
            counters.inserted = counters.markets;
            stateRepository.markSuccess(ENTITY_MARKETS, OffsetDateTime.now(ZoneOffset.UTC), null);
            stateRepository.finishRun(
                    runId, "success", counters.fetched, counters.inserted, counters.updated, counters.failed, null, null);
            log.info(
                    "Kalshi open markets ingestion complete: markets={} outcomes~={} failed={}",
                    counters.markets,
                    counters.outcomes,
                    counters.failed);
        } catch (RuntimeException ex) {
            stateRepository.markFailure(ENTITY_MARKETS, ex.getMessage());
            stateRepository.finishRun(
                    runId, "failed", counters.fetched, counters.inserted, counters.updated, counters.failed, null, ex.getMessage());
            throw ex;
        }
        return counters;
    }

    /**
     * Full catalog backfill: optional series refresh, then events (with nested markets +
     * outcomes), then open markets for anything not seen on events.
     */
    public CatalogIngestionResult ingestCatalog(boolean refreshSeries) {
        IngestionCounters series = null;
        if (refreshSeries) {
            series = ingestSeries(null);
        }
        IngestionCounters events = ingestEvents(true);
        IngestionCounters openMarkets = ingestOpenMarkets();
        return new CatalogIngestionResult(series, events, openMarkets);
    }

    public Long resolveSeriesMinUpdatedTs(Long configuredMinUpdatedTs) {
        if (configuredMinUpdatedTs != null) {
            return configuredMinUpdatedTs;
        }
        return stateRepository
                .lastSourceUpdatedAt(ENTITY_SERIES)
                .map(
                        ts ->
                                Math.max(
                                        0L,
                                        ts.toEpochSecond() - OVERLAP_SECONDS))
                .orElse(null);
    }

    public record CatalogIngestionResult(
            IngestionCounters series, IngestionCounters events, IngestionCounters openMarkets) {}

    public static final class IngestionCounters {
        int fetched;
        int inserted;
        int updated;
        int failed;
        int events;
        int markets;
        long outcomes;
    }
}
