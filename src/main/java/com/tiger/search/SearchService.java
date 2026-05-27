package com.tiger.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private final SearchRepository repository;

    public SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    public SearchResponse search(
            String q,
            String exchange,
            String category,
            String status,
            String seriesId,
            BigDecimal minVolume,
            BigDecimal minLiquidity,
            Integer limit,
            Integer offset) {
        SearchParameters parameters =
                new SearchParameters(
                        q,
                        exchange,
                        category,
                        status,
                        seriesId,
                        minVolume,
                        minLiquidity,
                        null,
                        null,
                        null,
                        SearchParameters.validateLimit(limit),
                        SearchParameters.validateOffset(offset));
        return new SearchResponse(
                repository.searchSeries(parameters),
                repository.searchEvents(parameters),
                repository.searchMarkets(parameters));
    }

    public List<SeriesResponse> searchSeries(
            String q,
            String exchange,
            String category,
            String status,
            Integer limit,
            Integer offset) {
        return repository.searchSeries(
                new SearchParameters(
                        q,
                        exchange,
                        category,
                        status,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        SearchParameters.validateLimit(limit),
                        SearchParameters.validateOffset(offset)));
    }

    public List<EventResponse> searchEvents(
            String q,
            String seriesId,
            String exchange,
            String category,
            String status,
            BigDecimal minVolume,
            OffsetDateTime discoveredAfter,
            Integer limit,
            Integer offset) {
        return repository.searchEvents(
                new SearchParameters(
                        q,
                        exchange,
                        category,
                        status,
                        seriesId,
                        minVolume,
                        null,
                        discoveredAfter,
                        null,
                        null,
                        SearchParameters.validateLimit(limit),
                        SearchParameters.validateOffset(offset)));
    }

    public List<MarketResponse> searchMarkets(
            String q,
            String seriesId,
            String exchange,
            String category,
            String status,
            BigDecimal minVolume,
            BigDecimal minLiquidity,
            OffsetDateTime settledAfter,
            OffsetDateTime settledBefore,
            Integer limit,
            Integer offset) {
        return repository.searchMarkets(
                new SearchParameters(
                        q,
                        exchange,
                        category,
                        status,
                        seriesId,
                        minVolume,
                        minLiquidity,
                        null,
                        settledAfter,
                        settledBefore,
                        SearchParameters.validateLimit(limit),
                        SearchParameters.validateOffset(offset)));
    }

    public List<EventResponse> recentEvents(
            String exchange, String status, Integer limit, Integer offset) {
        return repository.recentEvents(
                new SearchParameters(
                        null,
                        exchange,
                        null,
                        status,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        SearchParameters.validateLimit(limit),
                        SearchParameters.validateOffset(offset)));
    }

    public List<MarketResponse> recentMarkets(
            String exchange, String status, Integer limit, Integer offset) {
        return repository.recentMarkets(
                new SearchParameters(
                        null,
                        exchange,
                        null,
                        status,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        SearchParameters.validateLimit(limit),
                        SearchParameters.validateOffset(offset)));
    }

    public List<MarketResponse> marketsForEvent(
            String eventId, String status, Integer limit, Integer offset) {
        return repository.marketsForEvent(
                eventId,
                status == null || status.isBlank() ? null : status.trim(),
                SearchParameters.validateLimit(limit),
                SearchParameters.validateOffset(offset));
    }
}
