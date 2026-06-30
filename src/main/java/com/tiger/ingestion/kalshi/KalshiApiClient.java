package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.http.HttpClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConditionalOnExpression(
        "${tiger.ingestion.kalshi-series.enabled:false}"
                + " || ${tiger.ingestion.kalshi-events.enabled:false}"
                + " || ${tiger.ingestion.kalshi-open-markets.enabled:false}"
                + " || ${tiger.ingestion.kalshi-catalog.enabled:false}"
                + " || ${tiger.ingestion.kalshi-orderbook-snapshots.enabled:false}"
                + " || ${tiger.ingestion.kalshi-orderbook-snapshots.scheduler-enabled:false}")
public class KalshiApiClient {
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    private final RestClient restClient;
    private final TigerProperties.Kalshi kalshi;
    private final KalshiRequestSigner signer;

    public KalshiApiClient(RestClient.Builder restClientBuilder, TigerProperties properties) {
        this.kalshi = properties.kalshi();
        Duration connectTimeout = Duration.ofMillis(Math.max(1, kalshi.connectTimeoutMs()));
        Duration readTimeout = Duration.ofMillis(Math.max(1, kalshi.readTimeoutMs()));
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.signer = new KalshiRequestSigner(kalshi.keyId(), kalshi.privateKeyPath());
    }

    public JsonNode get(String endpoint, Map<String, ?> queryParams) {
        return executeWithRetry("GET", endpoint, queryParams);
    }

    /**
     * Fetches and processes each page before requesting the next (avoids holding the full catalog in heap).
     */
    public void forEachPage(
            String endpoint, String arrayKey, Map<String, ?> queryParams, KalshiPageConsumer consumer) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (queryParams != null) {
            params.putAll(queryParams);
        }
        String cursor = null;
        int pageIndex = 0;
        while (true) {
            params.remove("cursor");
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode payload = get(endpoint, params);
            JsonNode array = payload.path(arrayKey);
            String nextCursor = readCursor(payload.get("cursor"));
            consumer.accept(array, pageIndex, nextCursor);
            if (nextCursor == null) {
                break;
            }
            cursor = nextCursor;
            pageIndex++;
        }
    }

    private static String readCursor(JsonNode cursorNode) {
        if (cursorNode == null || cursorNode.isNull() || cursorNode.asText().isBlank()) {
            return null;
        }
        return cursorNode.asText();
    }

    private JsonNode executeWithRetry(String method, String endpoint, Map<String, ?> queryParams) {
        int attempts = Math.max(1, kalshi.maxRetries());
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                return executeOnce(method, endpoint, queryParams);
            } catch (KalshiRateLimitException ex) {
                lastFailure = ex;
                sleepBackoff(attempt);
            } catch (KalshiRetryableException ex) {
                lastFailure = ex;
                sleepBackoff(attempt);
            } catch (ResourceAccessException ex) {
                lastFailure = new KalshiRetryableException("Kalshi transport error: " + ex.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Kalshi request failed")
                : lastFailure;
    }

    private JsonNode executeOnce(String method, String endpoint, Map<String, ?> queryParams) {
        String signedPath = kalshi.apiPrefix() + endpoint;
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromHttpUrl(kalshi.resolvedBaseUrl() + signedPath);
        if (queryParams != null) {
            for (Map.Entry<String, ?> entry : queryParams.entrySet()) {
                if (entry.getValue() != null) {
                    builder.queryParam(entry.getKey(), entry.getValue());
                }
            }
        }
        URI uri = builder.build(true).toUri();
        HttpHeaders headers = signer.sign(method, signedPath);
        return restClient
                .get()
                .uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .exchange(
                        (request, response) -> {
                            HttpStatusCode status = response.getStatusCode();
                            if (status.value() == 429) {
                                throw new KalshiRateLimitException("Kalshi rate limit hit");
                            }
                            if (status.is5xxServerError()) {
                                throw new KalshiRetryableException(
                                        "Kalshi server error: " + status.value());
                            }
                            if (status.isError()) {
                                throw new IllegalStateException(
                                        "Kalshi request failed: "
                                                + status.value()
                                                + " "
                                                + response.bodyTo(String.class));
                            }
                            return response.bodyTo(JsonNode.class);
                        });
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF.multipliedBy(1L << attempt).toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Kalshi backoff", ex);
        }
    }

    static final class KalshiRateLimitException extends RuntimeException {
        KalshiRateLimitException(String message) {
            super(message);
        }
    }

    static final class KalshiRetryableException extends RuntimeException {
        KalshiRetryableException(String message) {
            super(message);
        }
    }
}
