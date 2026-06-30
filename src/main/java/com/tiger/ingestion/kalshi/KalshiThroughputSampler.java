package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiger.config.TigerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * One-shot CLI: sample Kalshi /series + /events (nested markets) for 60s and print sizing JSON.
 * Run: ./mvnw -q compile exec:java -Dexec.mainClass=com.tiger.ingestion.kalshi.KalshiThroughputSampler
 */
public final class KalshiThroughputSampler {
    private static final int DURATION_SEC = 60;
    private static final int EVENT_PAGE_LIMIT = 50;

    public static void main(String[] args) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Map<String, String> env = loadEnv(repoRoot.resolve(".env"));
        String keyId = env.getOrDefault("KALSHI_KEY_ID", "");
        String keyPath = env.getOrDefault("KALSHI_PRIVATE_KEY_PATH", "secrets/kalshi_private.key");
        String kalshiEnv = env.getOrDefault("KALSHI_ENV", "prod");
        if (keyId.isBlank()) {
            System.err.println("Missing KALSHI_KEY_ID in .env");
            System.exit(1);
        }

        TigerProperties.Kalshi kalshi =
                new TigerProperties.Kalshi(kalshiEnv, keyId, keyPath, "/trade-api/v2", 5, 4, 20_000, 30_000, 3);
        TigerProperties properties =
                new TigerProperties(
                        new TigerProperties.Polymarket(
                                "https://gamma-api.polymarket.com", "https://clob.polymarket.com", 100),
                        kalshi,
                        null);

        KalshiApiClient client = new KalshiApiClient(RestClient.builder(), properties);

        Stats stats = new Stats();
        JsonNode seriesPayload =
                client.get(
                        "/series",
                        Map.of(
                                "include_product_metadata", "true",
                                "include_volume", "true"));
        for (JsonNode series : seriesPayload.path("series")) {
            stats.series++;
            stats.seriesJsonBytes += jsonLen(series);
        }

        System.out.println("Sampling /events for " + DURATION_SEC + "s on " + kalshi.resolvedBaseUrl() + " ...");
        long deadline = System.nanoTime() + Duration.ofSeconds(DURATION_SEC).toNanos();
        String cursor = null;
        boolean cursorExhausted = false;
        while (System.nanoTime() < deadline) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("limit", EVENT_PAGE_LIMIT);
            query.put("with_nested_markets", "true");
            if (cursor != null) {
                query.put("cursor", cursor);
            }
            JsonNode payload = client.get("/events", query);
            stats.eventPages++;
            JsonNode events = payload.path("events");
            if (events.isArray()) {
                for (JsonNode event : events) {
                    stats.events++;
                    stats.eventJsonBytes += jsonLen(event);
                    JsonNode markets = event.path("markets");
                    if (markets.isArray()) {
                        for (JsonNode market : markets) {
                            stats.markets++;
                            stats.marketJsonBytes += jsonLen(market);
                            stats.outcomes += 2;
                        }
                    }
                }
            }
            JsonNode cursorNode = payload.get("cursor");
            if (cursorNode == null || cursorNode.isNull() || cursorNode.asText().isBlank()) {
                cursorExhausted = true;
                break;
            }
            cursor = cursorNode.asText();
        }

        double elapsedSec = DURATION_SEC;
        double eventsPerMin = stats.events * 60.0 / elapsedSec;
        double marketsPerMin = stats.markets * 60.0 / elapsedSec;
        double storePerMin = estimateStoreBytes(stats) * 60.0 / elapsedSec;

        System.out.println("--- Kalshi 1-minute sample (JSON sizing, not DB) ---");
        System.out.printf("series=%d events=%d markets=%d outcomes=%d event_pages=%d%n", stats.series, stats.events, stats.markets, stats.outcomes, stats.eventPages);
        System.out.printf("avg_json_bytes: series=%d event=%d market=%d%n",
                stats.series == 0 ? 0 : stats.seriesJsonBytes / stats.series,
                stats.events == 0 ? 0 : stats.eventJsonBytes / stats.events,
                stats.markets == 0 ? 0 : stats.marketJsonBytes / stats.markets);
        System.out.printf("per_minute: events=%.1f markets=%.1f est_pg_store=%s%n",
                eventsPerMin, marketsPerMin, pretty((long) storePerMin));
        System.out.printf("est_pg_store_60s_window=%s%n", pretty(estimateStoreBytes(stats)));
        System.out.printf("cursor_exhausted_in_window=%s%n", cursorExhausted);

        long minsPerMonth = 30L * 24 * 60;
        System.out.println("--- Monthly scenarios (from sample rates) ---");
        System.out.printf("continuous_at_sample_rate_30d=%s (upper bound, not realistic)%n",
                pretty((long) (storePerMin * minsPerMonth)));

        if (cursorExhausted) {
            System.out.printf("one_full_catalog_snapshot=%s%n", pretty(estimateStoreBytes(stats)));
        } else {
            System.out.println("one_full_catalog (multiply est_pg_store/min by backfill minutes):");
            for (int mins : new int[] {10, 20, 30, 45, 60, 90, 120}) {
                System.out.printf("  %d min backfill => %s%n", mins, pretty((long) (storePerMin * mins)));
            }
        }

        long baseSnap = cursorExhausted ? estimateStoreBytes(stats) : (long) (storePerMin * 30);
        long dailyUpdates = (long) (baseSnap * 0.15);
        System.out.printf("daily_full_reingest_30d=%s (1x snapshot + 29d ~15%% row updates)%n",
                pretty(baseSnap + dailyUpdates * 29));

        long netNewPerMin = (long) (storePerMin * 0.05);
        System.out.printf("net_new_rows_5pct_of_crawl_rate_30d=%s%n", pretty(netNewPerMin * minsPerMonth));
    }

    private static long estimateStoreBytes(Stats stats) {
        double seriesStore = stored(stats.seriesJsonBytes, stats.series, 1.4, 1.5);
        double eventsStore = stored(stats.eventJsonBytes, stats.events, 1.6, 1.7);
        double marketsStore = stored(stats.marketJsonBytes, stats.markets, 1.8, 1.9);
        double outcomesStore = stats.outcomes * 120 * 1.3 * 1.4;
        return (long) (seriesStore + eventsStore + marketsStore + outcomesStore);
    }

    private static double stored(long jsonBytes, int count, double heapFactor, double indexFactor) {
        if (count == 0) {
            return 0;
        }
        double avgJson = (double) jsonBytes / count;
        return count * avgJson * heapFactor * indexFactor;
    }

    private static int jsonLen(JsonNode node) {
        return node.toString().length();
    }

    private static String pretty(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f KB", bytes / 1024.0);
    }

    private static Map<String, String> loadEnv(Path path) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return env;
        }
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return env;
    }

    private static final class Stats {
        int series;
        int eventPages;
        int events;
        int markets;
        int outcomes;
        long seriesJsonBytes;
        long eventJsonBytes;
        long marketJsonBytes;
    }
}
