package com.tiger.ingestion.kalshi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiger.ingestion.kalshi.KalshiOrderbookResponse.PriceLevel;
import com.tiger.ingestion.kalshi.KalshiOrderbookSnapshotRepository.MarketBookSummary;
import com.tiger.ingestion.kalshi.KalshiOrderbookSnapshotRepository.TrackedMarket;
import com.tiger.ingestion.kalshi.KalshiOrderbookSnapshotRepository.TrackedOutcome;
import com.tiger.config.TigerProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KalshiOrderbookSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(KalshiOrderbookSnapshotService.class);
    private static final int STORED_LEVEL_LIMIT = 10;

    private final KalshiOrderbookClient orderbookClient;
    private final KalshiOrderbookSnapshotRepository repository;
    private final ObjectMapper objectMapper;
    private final TigerProperties properties;

    public KalshiOrderbookSnapshotService(
            KalshiOrderbookClient orderbookClient,
            KalshiOrderbookSnapshotRepository repository,
            ObjectMapper objectMapper,
            TigerProperties properties) {
        this.orderbookClient = orderbookClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public SnapshotPollResult pollActiveMarkets(int limit, int samples, long sampleIntervalMs) {
        return pollActiveMarkets(limit, samples, sampleIntervalMs, configuredParallelism());
    }

    public SnapshotPollResult pollActiveMarkets(int limit, int samples, long sampleIntervalMs, int parallelism) {
        validateLimit(limit);
        validateSamples(samples);
        validateSampleInterval(sampleIntervalMs);
        validateParallelism(parallelism);

        List<TrackedMarket> markets = repository.findActiveMarkets(limit);
        if (markets.isEmpty()) {
            log.info("Kalshi orderbook poll skipped: no active markets matched limit={}", limit);
            return new SnapshotPollResult(0, samples, 0, 0, 0, 0, parallelism);
        }

        log.info(
                "Kalshi orderbook poll starting: markets={} samples={} sampleIntervalMs={} parallelism={}",
                markets.size(),
                samples,
                sampleIntervalMs,
                parallelism);

        int totalInserted = 0;
        int totalSkipped = 0;
        int totalFailed = 0;
        for (int sample = 1; sample <= samples; sample++) {
            if (sample > 1 && sampleIntervalMs > 0) {
                sleep(sampleIntervalMs);
            }
            SnapshotRunResult round = snapshotMarkets(markets, sample, samples, parallelism);
            totalInserted += round.snapshotsInserted();
            totalSkipped += round.marketsSkipped();
            totalFailed += round.failures();
        }

        log.info(
                "Kalshi orderbook poll complete: markets={} samples={} inserted={} skipped={} failed={}",
                markets.size(),
                samples,
                totalInserted,
                totalSkipped,
                totalFailed);
        return new SnapshotPollResult(
                markets.size(), samples, totalInserted, totalSkipped, totalFailed, sampleIntervalMs, parallelism);
    }

    SnapshotRunResult snapshotMarkets(
            List<TrackedMarket> markets, int sampleIndex, int sampleCount, int parallelism) {
        int workers = effectiveParallelism(parallelism, markets.size());
        ExecutorService executor = Executors.newFixedThreadPool(workers, this::newWorkerThread);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            List<CompletableFuture<Void>> futures = markets.stream()
                    .map(market -> CompletableFuture.runAsync(
                            () -> {
                                MarketSnapshotStatus status = snapshotMarketSafely(market, sampleIndex, sampleCount);
                                switch (status) {
                                    case INSERTED -> inserted.incrementAndGet();
                                    case SKIPPED -> skipped.incrementAndGet();
                                    case FAILED -> failed.incrementAndGet();
                                }
                            },
                            executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            shutdownExecutor(executor);
        }

        if (!markets.isEmpty()) {
            log.info(
                    "Kalshi orderbook sample {}/{} complete: scanned={} inserted={} skipped={} failed={} parallelism={}",
                    sampleIndex,
                    sampleCount,
                    markets.size(),
                    inserted.get(),
                    skipped.get(),
                    failed.get(),
                    workers);
        }
        return new SnapshotRunResult(markets.size(), inserted.get(), skipped.get(), failed.get());
    }

    private MarketSnapshotStatus snapshotMarketSafely(TrackedMarket market, int sampleIndex, int sampleCount) {
        try {
            SnapshotResult result = snapshotMarket(market);
            return result.inserted() ? MarketSnapshotStatus.INSERTED : MarketSnapshotStatus.SKIPPED;
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to snapshot Kalshi orderbook for {} (sample {}/{}): {}",
                    market.publicId(),
                    sampleIndex,
                    sampleCount,
                    exception.getMessage());
            return MarketSnapshotStatus.FAILED;
        }
    }

    private Thread newWorkerThread(Runnable task) {
        Thread thread = new Thread(task);
        thread.setName("kalshi-orderbook-snapshot-" + thread.threadId());
        thread.setDaemon(true);
        return thread;
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IllegalStateException("Interrupted while waiting for Kalshi orderbook workers", exception);
        }
    }

    private int configuredParallelism() {
        return properties.ingestion().kalshiOrderbookSnapshots().parallelism();
    }

    private int effectiveParallelism(int parallelism, int marketCount) {
        return Math.min(Math.max(parallelism, 1), marketCount);
    }

    SnapshotResult snapshotMarket(TrackedMarket market) {
        Optional<KalshiOrderbookResponse> bookOptional = orderbookClient.fetchOrderbook(market.sourceMarketId());
        if (bookOptional.isEmpty()) {
            return SnapshotResult.skippedResult();
        }
        KalshiOrderbookResponse book = bookOptional.get();

        ArrayNode orderbooksJson = objectMapper.createArrayNode();
        int booksWritten = 0;
        for (TrackedOutcome outcome : market.outcomes()) {
            OutcomeBook outcomeBook = outcomeBook(outcome, book);
            if (outcomeBook == null) {
                continue;
            }
            repository.updateOutcomePrices(outcome.outcomeId(), outcomeBook.bestBid(), outcomeBook.bestAsk());
            orderbooksJson.add(toJson(market.sourceMarketId(), outcome, outcomeBook));
            booksWritten++;
        }

        if (booksWritten == 0) {
            return SnapshotResult.skippedResult();
        }

        MarketBookSummary marketSummary =
                new MarketBookSummary(booksWritten, book.bestYesBid(), book.bestYesAsk(), null, book.bestNoBid(), book.bestNoAsk(), null);
        repository.updateMarketPrices(market.marketId(), marketSummary);
        repository.insertSnapshot(market, marketSummary, json(orderbooksJson));
        return SnapshotResult.insertedResult();
    }

    private OutcomeBook outcomeBook(TrackedOutcome outcome, KalshiOrderbookResponse book) {
        if (outcome.side() == null) {
            return null;
        }
        return switch (outcome.side().toUpperCase(Locale.ROOT)) {
            case "YES" -> new OutcomeBook(book.bestYesBid(), book.bestYesAsk(), book.yesBidLevels());
            case "NO" -> new OutcomeBook(book.bestNoBid(), book.bestNoAsk(), book.noBidLevels());
            default -> null;
        };
    }

    private ObjectNode toJson(String ticker, TrackedOutcome outcome, OutcomeBook outcomeBook) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("outcome_key", outcome.outcomeKey());
        node.put("outcome_name", outcome.outcomeName());
        node.put("side", outcome.side());
        node.put("market_ticker", ticker);
        node.put("source", "kalshi_trade_api");
        putDecimal(node, "best_bid", outcomeBook.bestBid());
        putDecimal(node, "best_ask", outcomeBook.bestAsk());
        node.set("bids", levels(outcomeBook.bidLevels()));
        node.set("asks", objectMapper.createArrayNode());
        return node;
    }

    private ArrayNode levels(List<PriceLevel> levels) {
        ArrayNode array = objectMapper.createArrayNode();
        levels.stream().limit(STORED_LEVEL_LIMIT).forEach(level -> {
            ObjectNode node = objectMapper.createObjectNode();
            putDecimal(node, "price", price(level.price()));
            putDecimal(node, "size", level.size());
            array.add(node);
        });
        return array;
    }

    private void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.set(field, JsonNodeFactory.instance.nullNode());
        } else {
            node.set(field, JsonNodeFactory.instance.numberNode(value));
        }
    }

    private BigDecimal price(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize orderbook snapshot", exception);
        }
    }

    private void validateLimit(int limit) {
        if (limit < 0 || limit > 500) {
            throw new IllegalArgumentException("Kalshi orderbook snapshot limit must be 0 (all) or between 1 and 500");
        }
    }

    private void validateSamples(int samples) {
        if (samples < 1 || samples > 100) {
            throw new IllegalArgumentException("Kalshi orderbook snapshot samples must be between 1 and 100");
        }
    }

    private void validateSampleInterval(long sampleIntervalMs) {
        if (sampleIntervalMs < 0) {
            throw new IllegalArgumentException("Kalshi orderbook sample interval must be >= 0");
        }
    }

    private void validateParallelism(int parallelism) {
        if (parallelism < 1 || parallelism > 128) {
            throw new IllegalArgumentException("Kalshi orderbook snapshot parallelism must be between 1 and 128");
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Kalshi orderbook poll", exception);
        }
    }

    public record SnapshotPollResult(
            int marketsScanned,
            int samplesTaken,
            int snapshotsInserted,
            int marketsSkipped,
            int failures,
            long sampleIntervalMs,
            int parallelism) {}

    public record SnapshotRunResult(int marketsScanned, int snapshotsInserted, int marketsSkipped, int failures) {}

    record SnapshotResult(boolean inserted) {
        static SnapshotResult insertedResult() {
            return new SnapshotResult(true);
        }

        static SnapshotResult skippedResult() {
            return new SnapshotResult(false);
        }
    }

    private record OutcomeBook(BigDecimal bestBid, BigDecimal bestAsk, List<PriceLevel> bidLevels) {}

    private enum MarketSnapshotStatus {
        INSERTED,
        SKIPPED,
        FAILED
    }
}
