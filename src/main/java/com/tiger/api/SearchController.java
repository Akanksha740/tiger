package com.tiger.api;

import com.tiger.search.EventResponse;
import com.tiger.search.MarketResponse;
import com.tiger.search.SearchResponse;
import com.tiger.search.SearchService;
import com.tiger.search.SeriesResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
public class SearchController {
    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "series_id") String seriesId,
            @RequestParam(required = false, name = "min_volume") BigDecimal minVolume,
            @RequestParam(required = false, name = "min_liquidity") BigDecimal minLiquidity,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.search(
                q, exchange, category, status, seriesId, minVolume, minLiquidity, limit, offset);
    }

    @GetMapping("/series")
    public List<SeriesResponse> searchSeries(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.searchSeries(q, exchange, category, status, limit, offset);
    }

    @GetMapping("/events")
    public List<EventResponse> searchEvents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, name = "series_id") String seriesId,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "min_volume") BigDecimal minVolume,
            @RequestParam(required = false, name = "discovered_after")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime discoveredAfter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.searchEvents(
                q, seriesId, exchange, category, status, minVolume, discoveredAfter, limit, offset);
    }

    @GetMapping("/markets")
    public List<MarketResponse> searchMarkets(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, name = "series_id") String seriesId,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "min_volume") BigDecimal minVolume,
            @RequestParam(required = false, name = "min_liquidity") BigDecimal minLiquidity,
            @RequestParam(required = false, name = "settled_after")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime settledAfter,
            @RequestParam(required = false, name = "settled_before")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime settledBefore,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.searchMarkets(
                q,
                seriesId,
                exchange,
                category,
                status,
                minVolume,
                minLiquidity,
                settledAfter,
                settledBefore,
                limit,
                offset);
    }

    @GetMapping("/recent/events")
    public List<EventResponse> recentEvents(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.recentEvents(exchange, status, limit, offset);
    }

    @GetMapping("/recent/markets")
    public List<MarketResponse> recentMarkets(
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.recentMarkets(exchange, status, limit, offset);
    }

    @GetMapping("/events/{eventId}/markets")
    public List<MarketResponse> marketsForEvent(
            @PathVariable String eventId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return searchService.marketsForEvent(eventId, status, limit, offset);
    }
}
