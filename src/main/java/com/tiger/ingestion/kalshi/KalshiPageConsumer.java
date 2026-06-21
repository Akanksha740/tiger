package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;

/** Processes one Kalshi API page without retaining prior pages in memory. */
@FunctionalInterface
interface KalshiPageConsumer {
    /**
     * @param items array node for the page (e.g. payload.path("events"))
     * @param pageIndex zero-based page number
     * @param cursorAfter cursor for the next page, or null if this was the last page
     */
    void accept(JsonNode items, int pageIndex, String cursorAfter);
}
