package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PolymarketIngestionService {
    private static final Logger log = LoggerFactory.getLogger(PolymarketIngestionService.class);
    private static final String ENTITY_EVENTS = "events";

    private final PolymarketGammaClient client;
    private final PolymarketNormalizer normalizer;
    private final PolymarketIngestionRepository repository;
    private final PolymarketIngestionStateRepository stateRepository;
    private final TigerProperties properties;

    public PolymarketIngestionService(
            PolymarketGammaClient client,
            PolymarketNormalizer normalizer,
            PolymarketIngestionRepository repository,
            PolymarketIngestionStateRepository stateRepository,
            TigerProperties properties) {
        this.client = client;
        this.normalizer = normalizer;
        this.repository = repository;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    public IngestionResult ingestEventsPage(Integer limit, Integer offset) {
        int pageLimit = limit == null ? properties.polymarket().pageLimit() : limit;
        int pageOffset = offset == null ? 0 : offset;
        IngestionCounters counters = ingestEventsPages(pageLimit, pageOffset, 1);
        return new IngestionResult(counters.events, counters.markets);
    }

    public IngestionCounters ingestCatalog(int pageLimit, int startOffset, int maxPages) {
        return ingestEventsPages(pageLimit, startOffset, maxPages);
    }

    private IngestionCounters ingestEventsPages(int pageLimit, int startOffset, int maxPages) {
        validatePaging(pageLimit, startOffset, maxPages);

        stateRepository.markAttempt(ENTITY_EVENTS, "/events", String.valueOf(startOffset));
        UUID runId = stateRepository.startRun(ENTITY_EVENTS, String.valueOf(startOffset));
        IngestionCounters counters = new IngestionCounters(startOffset);
        try {
            int offset = startOffset;
            while (maxPages <= 0 || counters.pages < maxPages) {
                JsonNode response = client.fetchEventsPage(pageLimit, offset);
                List<NormalizedEvent> events = normalizer.normalizeEvents(response);
                counters.pages++;
                counters.fetched += events.size();

                for (NormalizedEvent event : events) {
                    try {
                        repository.upsertEventTree(event);
                        counters.events++;
                        counters.markets += event.markets().size();
                        counters.outcomes += event.markets().stream()
                                .mapToLong(market -> market.outcomes().size())
                                .sum();
                    } catch (RuntimeException ex) {
                        counters.failed++;
                        log.warn("Failed Polymarket event tree upsert for {}", event.sourceEventId(), ex);
                    }
                }

                counters.nextOffset = offset + events.size();
                log.info(
                        "Polymarket events page complete: offset={} pageEvents={} totalEvents={} terminal={}",
                        offset,
                        events.size(),
                        counters.events,
                        events.size() < pageLimit);
                if (events.size() < pageLimit) {
                    counters.terminalPage = true;
                    break;
                }
                offset += pageLimit;
            }

            String status = counters.failed == 0 ? "success" : "partial_success";
            stateRepository.markSuccess(
                    ENTITY_EVENTS, OffsetDateTime.now(ZoneOffset.UTC), String.valueOf(counters.nextOffset));
            stateRepository.finishRun(
                    runId,
                    status,
                    counters.fetched,
                    counters.events,
                    0,
                    counters.failed,
                    String.valueOf(counters.nextOffset),
                    null);
            return counters;
        } catch (RuntimeException ex) {
            stateRepository.markFailure(ENTITY_EVENTS, ex.getMessage());
            stateRepository.finishRun(
                    runId,
                    "failed",
                    counters.fetched,
                    counters.events,
                    0,
                    counters.failed,
                    String.valueOf(counters.nextOffset),
                    ex.getMessage());
            throw ex;
        }
    }

    private void validatePaging(int pageLimit, int startOffset, int maxPages) {
        if (pageLimit < 1 || pageLimit > 500) {
            throw new IllegalArgumentException("Polymarket ingestion limit must be between 1 and 500");
        }
        if (startOffset < 0) {
            throw new IllegalArgumentException("Polymarket ingestion offset must be zero or greater");
        }
        if (maxPages < 0) {
            throw new IllegalArgumentException("Polymarket max pages must be zero or greater");
        }
    }

    public record IngestionResult(int events, int markets) {}

    public static final class IngestionCounters {
        public final int startOffset;
        public int nextOffset;
        public int pages;
        public int fetched;
        public int events;
        public int markets;
        public long outcomes;
        public int failed;
        public boolean terminalPage;

        IngestionCounters(int startOffset) {
            this.startOffset = startOffset;
            this.nextOffset = startOffset;
        }
    }
}
