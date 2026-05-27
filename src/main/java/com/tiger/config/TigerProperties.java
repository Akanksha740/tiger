package com.tiger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tiger")
public record TigerProperties(Polymarket polymarket, Ingestion ingestion) {
    public record Polymarket(String gammaBaseUrl, int pageLimit) {}

    public record Ingestion(PolymarketEvents polymarketEvents, boolean exitOnComplete) {}

    public record PolymarketEvents(boolean enabled, int limit, int offset) {}
}
