package com.tiger.ingestion.polymarket;

import com.tiger.config.TigerProperties;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PolymarketClobClient {
    private static final Logger log = LoggerFactory.getLogger(PolymarketClobClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

    private final RestClient restClient;
    private final TigerProperties properties;

    public PolymarketClobClient(RestClient.Builder restClientBuilder, TigerProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public List<PolymarketOrderbookResponse> fetchOrderbooks(List<String> tokenIds) {
        List<TokenRequest> requestBody = tokenIds(tokenIds).stream().map(TokenRequest::new).toList();
        if (requestBody.isEmpty()) {
            return List.of();
        }

        URI uri = URI.create(properties.polymarket().clobBaseUrl() + "/books");
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                List<PolymarketOrderbookResponse> response = restClient
                        .post()
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<PolymarketOrderbookResponse>>() {});
                return response == null ? List.of() : response;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (attempt + 1 < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }

        log.warn("Failed to fetch Polymarket CLOB books for {} token(s): {}", requestBody.size(),
                lastFailure == null ? "unknown error" : lastFailure.getMessage());
        return List.of();
    }

    private List<String> tokenIds(List<String> tokenIds) {
        if (tokenIds == null || tokenIds.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String tokenId : tokenIds) {
            if (tokenId != null && !tokenId.isBlank()) {
                distinct.add(tokenId.trim());
            }
        }
        return new ArrayList<>(distinct);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF.multipliedBy(1L << attempt).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Polymarket CLOB backoff", exception);
        }
    }

    private record TokenRequest(@com.fasterxml.jackson.annotation.JsonProperty("token_id") String tokenId) {}
}
