package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PolymarketIngestionService {
    private final PolymarketGammaClient client;
    private final PolymarketNormalizer normalizer;
    private final PolymarketIngestionRepository repository;
    private final TigerProperties properties;

    public PolymarketIngestionService(
            PolymarketGammaClient client,
            PolymarketNormalizer normalizer,
            PolymarketIngestionRepository repository,
            TigerProperties properties) {
        this.client = client;
        this.normalizer = normalizer;
        this.repository = repository;
        this.properties = properties;
    }

    public IngestionResult ingestEventsPage(Integer limit, Integer offset) {
        int pageLimit = limit == null ? properties.polymarket().pageLimit() : limit;
        int pageOffset = offset == null ? 0 : offset;
        if (pageLimit < 1 || pageLimit > 500) {
            throw new IllegalArgumentException("Polymarket ingestion limit must be between 1 and 500");
        }
        if (pageOffset < 0) {
            throw new IllegalArgumentException("Polymarket ingestion offset must be zero or greater");
        }

        JsonNode response = client.fetchEventsPage(pageLimit, pageOffset);
        List<NormalizedEvent> events = normalizer.normalizeEvents(response);
        for (NormalizedEvent event : events) {
            repository.upsertEventTree(event);
        }
        int markets = events.stream().mapToInt(event -> event.markets().size()).sum();
        return new IngestionResult(events.size(), markets);
    }

    public record IngestionResult(int events, int markets) {}
}
