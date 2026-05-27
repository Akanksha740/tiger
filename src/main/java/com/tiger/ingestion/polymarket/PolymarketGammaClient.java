package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PolymarketGammaClient {
    private final RestClient restClient;
    private final TigerProperties properties;

    public PolymarketGammaClient(RestClient.Builder restClientBuilder, TigerProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public JsonNode fetchEventsPage(int limit, int offset) {
        URI uri =
                UriComponentsBuilder.fromHttpUrl(properties.polymarket().gammaBaseUrl())
                        .path("/events")
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .queryParam("order", "id")
                        .build()
                        .toUri();
        return restClient.get().uri(uri).retrieve().body(JsonNode.class);
    }
}
