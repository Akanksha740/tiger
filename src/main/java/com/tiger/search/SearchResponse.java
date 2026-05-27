package com.tiger.search;

import java.util.List;

public record SearchResponse(
        List<SeriesResponse> series,
        List<EventResponse> events,
        List<MarketResponse> markets) {}
