package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
public class KalshiOrderbookClient {
    private static final Logger log = LoggerFactory.getLogger(KalshiOrderbookClient.class);
    private static final int DEFAULT_DEPTH = 10;

    private final KalshiApiClient apiClient;
    private final ObjectMapper objectMapper;

    public KalshiOrderbookClient(KalshiApiClient apiClient, ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
    }

    public Optional<KalshiOrderbookResponse> fetchOrderbook(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Optional.empty();
        }
        try {
            String encodedTicker = UriUtils.encodePathSegment(ticker.trim(), StandardCharsets.UTF_8);
            JsonNode payload = apiClient.get(
                    "/markets/" + encodedTicker + "/orderbook", Map.of("depth", DEFAULT_DEPTH));
            KalshiOrderbookResponse response = objectMapper.treeToValue(payload, KalshiOrderbookResponse.class);
            if (response == null || !response.hasLiquidity()) {
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (RuntimeException exception) {
            log.warn("Failed to fetch Kalshi orderbook for {}: {}", ticker, exception.getMessage());
            return Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("Failed to parse Kalshi orderbook for {}: {}", ticker, exception.getMessage());
            return Optional.empty();
        }
    }
}
