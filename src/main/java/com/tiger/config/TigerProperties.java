package com.tiger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tiger")
public record TigerProperties(
        Polymarket polymarket, Kalshi kalshi, Ingestion ingestion) {
    public record Polymarket(String gammaBaseUrl, int pageLimit) {}

    public record Kalshi(String env, String keyId, String privateKeyPath, String apiPrefix, int maxRetries) {
        public String resolvedBaseUrl() {
            return switch (env == null ? "demo" : env.toLowerCase()) {
                case "prod", "production" -> "https://external-api.kalshi.com";
                case "demo" -> "https://external-api.demo.kalshi.co";
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported tiger.kalshi.env: " + env + " (use demo or prod)");
            };
        }
    }

    public record Ingestion(
            PolymarketEvents polymarketEvents,
            PolymarketCatalog polymarketCatalog,
            KalshiSeries kalshiSeries,
            KalshiEvents kalshiEvents,
            KalshiOpenMarkets kalshiOpenMarkets,
            KalshiCatalog kalshiCatalog,
            boolean exitOnComplete) {}

    public record PolymarketEvents(boolean enabled, int limit, int offset) {}

    /**
     * Backfills Gamma /events with nested markets. maxPages = 0 means keep
     * paging until Gamma returns fewer than pageLimit events.
     */
    public record PolymarketCatalog(boolean enabled, int pageLimit, int startOffset, int maxPages) {}

    public record KalshiSeries(boolean enabled, Long minUpdatedTs) {}

    public record KalshiEvents(boolean enabled, int pageLimit, boolean withNestedMarkets) {}

    public record KalshiOpenMarkets(boolean enabled, int pageLimit) {}

    /** Runs events (nested markets + outcomes) then open /markets. */
    public record KalshiCatalog(boolean enabled, boolean refreshSeries) {}
}
