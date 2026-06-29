package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression(
        "${tiger.ingestion.kalshi-series.enabled:false}"
                + " || ${tiger.ingestion.kalshi-events.enabled:false}"
                + " || ${tiger.ingestion.kalshi-open-markets.enabled:false}"
                + " || ${tiger.ingestion.kalshi-catalog.enabled:false}")
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

    public SeriesIngestionResult ingestSeries(Long minUpdatedTs) {
        stateRepository.markAttempt(ENTITY_SERIES, "/series");
        UUID runId = stateRepository.startRun(ENTITY_SERIES);
        IngestionCounters counters = new IngestionCounters();
        List<String> insertedSeriesTickers = new ArrayList<>();
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
                        insertedSeriesTickers.add(item.sourceSeriesId());
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
                    "Kalshi series ingestion complete: fetched={} inserted={} updated={} failed={} newSeries={}",
                    counters.fetched,
                    counters.inserted,
                    counters.updated,
                    counters.failed,
                    insertedSeriesTickers.size());
        } catch (RuntimeException ex) {
            stateRepository.markFailure(ENTITY_SERIES, ex.getMessage());
            stateRepository.finishRun(
                    runId, "failed", counters.fetched, counters.inserted, counters.updated, counters.failed, null, ex.getMessage());
            throw ex;
        }
        return new SeriesIngestionResult(counters, List.copyOf(insertedSeriesTickers));
    }

    /** Full catalog scan of {@code GET /events} (no series or min_updated_ts filters). */
    public IngestionCounters ingestEvents(boolean withNestedMarkets) {
        return ingestEventsPass(EventsFetchSpec.full(withNestedMarkets), true);
    }

    /**
     * Incremental events sync: bootstrap events for newly inserted series, then poll Kalshi for
     * events updated since the last successful events ingest.
     */
    public IngestionCounters ingestEventsIncremental(
            List<String> newSeriesTickers, Long minUpdatedTs, boolean withNestedMarkets) {
        stateRepository.markAttempt(ENTITY_EVENTS, "/events");
        UUID runId = stateRepository.startRun(ENTITY_EVENTS);
        IngestionCounters counters = new IngestionCounters();
        try {
            Set<String> seriesToBootstrap = distinctSeriesTickers(newSeriesTickers);
            for (String seriesTicker : seriesToBootstrap) {
                log.info("Kalshi events bootstrap for new series {}", seriesTicker);
                fetchEventsPages(EventsFetchSpec.forSeries(seriesTicker, withNestedMarkets), counters);
            }
            if (minUpdatedTs != null) {
                log.info("Kalshi events delta ingest with min_updated_ts={}", minUpdatedTs);
                fetchEventsPages(EventsFetchSpec.delta(minUpdatedTs, withNestedMarkets), counters);
            } else if (seriesToBootstrap.isEmpty()) {
                log.info(
                        "Kalshi events incremental skipped: no new series and no events watermark yet "
                                + "(run a full catalog backfill first)");
            }
            counters.inserted = counters.events;
            counters.updated = 0;
            stateRepository.markSuccess(ENTITY_EVENTS, OffsetDateTime.now(ZoneOffset.UTC), null);
            stateRepository.finishRun(
                    runId, "success", counters.fetched, counters.inserted, counters.updated, counters.failed, null, null);
            log.info(
                    "Kalshi events incremental complete: newSeries={} events={} markets={} outcomes~={} failed={}",
                    seriesToBootstrap.size(),
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
            params.put("include_volume", "true");
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
            series = ingestSeries(null).counters();
        }
        IngestionCounters events = ingestEvents(true);
        IngestionCounters openMarkets = ingestOpenMarkets();
        return new CatalogIngestionResult(series, events, openMarkets);
    }

    /**
     * Incremental catalog sync: incremental (or full) series, events for newly inserted series,
     * events updated since last watermark, then open markets.
     */
    public IncrementalCatalogIngestionResult ingestCatalogIncremental(boolean refreshSeries) {
        Long seriesMinUpdatedTs = refreshSeries ? null : resolveSeriesMinUpdatedTs(null);
        SeriesIngestionResult seriesResult = ingestSeries(seriesMinUpdatedTs);
        Long eventsMinUpdatedTs = resolveEventsMinUpdatedTs(null);
        IngestionCounters events =
                ingestEventsIncremental(seriesResult.insertedSeriesTickers(), eventsMinUpdatedTs, true);
        IngestionCounters openMarkets = ingestOpenMarkets();
        return new IncrementalCatalogIngestionResult(seriesResult, events, openMarkets);
    }

    public Long resolveSeriesMinUpdatedTs(Long configuredMinUpdatedTs) {
        if (configuredMinUpdatedTs != null) {
            return configuredMinUpdatedTs;
        }
        return stateRepository
                .lastSourceUpdatedAt(ENTITY_SERIES)
                .map(ts -> Math.max(0L, ts.toEpochSecond() - OVERLAP_SECONDS))
                .orElse(null);
    }

    public Long resolveEventsMinUpdatedTs(Long configuredMinUpdatedTs) {
        if (configuredMinUpdatedTs != null) {
            return configuredMinUpdatedTs;
        }
        return stateRepository
                .lastSourceUpdatedAt(ENTITY_EVENTS)
                .map(ts -> Math.max(0L, ts.toEpochSecond() - OVERLAP_SECONDS))
                .orElse(null);
    }

    private IngestionCounters ingestEventsPass(EventsFetchSpec spec, boolean recordRun) {
        if (recordRun) {
            stateRepository.markAttempt(ENTITY_EVENTS, "/events");
        }
        UUID runId = recordRun ? stateRepository.startRun(ENTITY_EVENTS) : null;
        IngestionCounters counters = new IngestionCounters();
        try {
            fetchEventsPages(spec, counters);
            counters.inserted = counters.events;
            counters.updated = 0;
            if (recordRun) {
                stateRepository.markSuccess(ENTITY_EVENTS, OffsetDateTime.now(ZoneOffset.UTC), null);
                stateRepository.finishRun(
                        runId,
                        "success",
                        counters.fetched,
                        counters.inserted,
                        counters.updated,
                        counters.failed,
                        null,
                        null);
            }
            logEventsPassComplete(spec, counters);
        } catch (RuntimeException ex) {
            if (recordRun) {
                stateRepository.markFailure(ENTITY_EVENTS, ex.getMessage());
                stateRepository.finishRun(
                        runId,
                        "failed",
                        counters.fetched,
                        counters.inserted,
                        counters.updated,
                        counters.failed,
                        null,
                        ex.getMessage());
            }
            throw ex;
        }
        return counters;
    }

    private IngestionCounters fetchEventsPages(EventsFetchSpec spec, IngestionCounters counters) {
        Map<String, Object> params = buildEventsParams(spec);
        String scopeLabel = describeEventsScope(spec);
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
                            "Kalshi events page {} complete ({}): pageEvents={} totalEvents={} cursor={}",
                            pageIndex,
                            scopeLabel,
                            pageEvents,
                            counters.events,
                            cursorAfter == null ? "done" : "more");
                });
        return counters;
    }

    private Map<String, Object> buildEventsParams(EventsFetchSpec spec) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", properties.ingestion().kalshiEvents().pageLimit());
        params.put("with_nested_markets", String.valueOf(spec.withNestedMarkets()).toLowerCase());
        params.put("include_volume", "true");
        if (spec.seriesTicker() != null) {
            params.put("series_ticker", spec.seriesTicker());
        }
        if (spec.minUpdatedTs() != null) {
            params.put("min_updated_ts", spec.minUpdatedTs());
        }
        return params;
    }

    private static String describeEventsScope(EventsFetchSpec spec) {
        if (spec.seriesTicker() != null) {
            return "series=" + spec.seriesTicker();
        }
        if (spec.minUpdatedTs() != null) {
            return "min_updated_ts=" + spec.minUpdatedTs();
        }
        return "full catalog";
    }

    private static void logEventsPassComplete(EventsFetchSpec spec, IngestionCounters counters) {
        log.info(
                "Kalshi events ingestion complete ({}): events={} markets={} outcomes~={} failed={}",
                describeEventsScope(spec),
                counters.events,
                counters.markets,
                counters.outcomes,
                counters.failed);
    }

    private static Set<String> distinctSeriesTickers(List<String> seriesTickers) {
        Set<String> distinct = new LinkedHashSet<>();
        if (seriesTickers == null) {
            return distinct;
        }
        for (String ticker : seriesTickers) {
            if (ticker != null && !ticker.isBlank()) {
                distinct.add(ticker.trim());
            }
        }
        return distinct;
    }

    public record SeriesIngestionResult(IngestionCounters counters, List<String> insertedSeriesTickers) {}

    public record CatalogIngestionResult(
            IngestionCounters series, IngestionCounters events, IngestionCounters openMarkets) {}

    public record IncrementalCatalogIngestionResult(
            SeriesIngestionResult series, IngestionCounters events, IngestionCounters openMarkets) {}

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
