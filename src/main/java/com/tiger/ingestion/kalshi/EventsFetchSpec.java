package com.tiger.ingestion.kalshi;

/**
 * Query parameters for a single {@code GET /events} ingestion pass.
 *
 * @param withNestedMarkets include nested markets and outcomes on each event
 * @param seriesTicker when set, limits the pass to one Kalshi series
 * @param minUpdatedTs when set, limits the pass to events updated after this epoch second
 */
record EventsFetchSpec(boolean withNestedMarkets, String seriesTicker, Long minUpdatedTs) {
    static EventsFetchSpec full(boolean withNestedMarkets) {
        return new EventsFetchSpec(withNestedMarkets, null, null);
    }

    static EventsFetchSpec forSeries(String seriesTicker, boolean withNestedMarkets) {
        if (seriesTicker == null || seriesTicker.isBlank()) {
            throw new IllegalArgumentException("seriesTicker is required");
        }
        return new EventsFetchSpec(withNestedMarkets, seriesTicker.trim(), null);
    }

    static EventsFetchSpec delta(Long minUpdatedTs, boolean withNestedMarkets) {
        if (minUpdatedTs == null) {
            throw new IllegalArgumentException("minUpdatedTs is required");
        }
        return new EventsFetchSpec(withNestedMarkets, null, minUpdatedTs);
    }

    boolean isScoped() {
        return (seriesTicker != null && !seriesTicker.isBlank()) || minUpdatedTs != null;
    }
}
