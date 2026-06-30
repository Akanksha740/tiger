package com.tiger.ingestion.polymarket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotRepository.MarketBookSummary;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotRepository.TrackedMarket;
import com.tiger.ingestion.polymarket.PolymarketOrderbookSnapshotRepository.TrackedOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PolymarketOrderbookSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(PolymarketOrderbookSnapshotService.class);
    private static final int STORED_LEVEL_LIMIT = 10;

    private final PolymarketClobClient clobClient;
    private final PolymarketOrderbookSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public PolymarketOrderbookSnapshotService(
            PolymarketClobClient clobClient,
            PolymarketOrderbookSnapshotRepository repository,
            ObjectMapper objectMapper) {
        this.clobClient = clobClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public SnapshotRunResult snapshotActiveMarkets(int limit) {
        validateLimit(limit);

        List<TrackedMarket> markets = repository.findActiveMarketsWithTokenIds(limit);
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        for (TrackedMarket market : markets) {
            try {
                SnapshotResult result = snapshotMarket(market);
                if (result.inserted()) {
                    inserted++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Failed to snapshot Polymarket orderbook for {}: {}", market.publicId(), exception.getMessage());
            }
        }

        if (!markets.isEmpty()) {
            log.info(
                    "Polymarket orderbook snapshots complete: scanned={} inserted={} skipped={} failed={}",
                    markets.size(),
                    inserted,
                    skipped,
                    failed);
        }
        return new SnapshotRunResult(markets.size(), inserted, skipped, failed);
    }

    SnapshotResult snapshotMarket(TrackedMarket market) {
        List<String> tokenIds = market.outcomes().stream().map(TrackedOutcome::tokenId).toList();
        Map<String, PolymarketOrderbookResponse> booksByToken = clobClient.fetchOrderbooks(tokenIds).stream()
                .filter(book -> book.assetId() != null)
                .collect(Collectors.toMap(
                        PolymarketOrderbookResponse::assetId,
                        Function.identity(),
                        (left, ignored) -> left,
                        LinkedHashMap::new));

        if (booksByToken.isEmpty()) {
            return SnapshotResult.skippedResult();
        }

        ArrayNode orderbooksJson = objectMapper.createArrayNode();
        MutableSummary summary = new MutableSummary();
        for (TrackedOutcome outcome : market.outcomes()) {
            PolymarketOrderbookResponse book = booksByToken.get(outcome.tokenId());
            if (book == null) {
                continue;
            }
            BigDecimal bestBid = price(book.bestBid());
            BigDecimal bestAsk = price(book.bestAsk());
            BigDecimal lastPrice = price(book.lastTradePriceDecimal());
            summary.accept(outcome.side(), bestBid, bestAsk, lastPrice);
            repository.updateOutcomePrices(outcome.outcomeId(), bestBid, bestAsk, lastPrice);
            orderbooksJson.add(toJson(outcome, book, bestBid, bestAsk, lastPrice));
        }

        MarketBookSummary marketSummary = summary.toSummary(orderbooksJson.size());
        if (marketSummary.bookCount() == 0) {
            return SnapshotResult.skippedResult();
        }

        repository.updateMarketPrices(market.marketId(), marketSummary);
        repository.insertSnapshot(market, marketSummary, json(orderbooksJson));
        return SnapshotResult.insertedResult();
    }

    private ObjectNode toJson(
            TrackedOutcome outcome,
            PolymarketOrderbookResponse book,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            BigDecimal lastPrice) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("outcome_key", outcome.outcomeKey());
        node.put("outcome_name", outcome.outcomeName());
        node.put("side", outcome.side());
        node.put("token_id", outcome.tokenId());
        node.put("asset_id", book.assetId());
        node.put("source_market", book.market());
        node.put("source_timestamp", book.timestamp());
        putDecimal(node, "best_bid", bestBid);
        putDecimal(node, "best_ask", bestAsk);
        putDecimal(node, "last_trade_price", lastPrice);
        node.set("bids", levels(book.bids(), Comparator.reverseOrder()));
        node.set("asks", levels(book.asks(), Comparator.naturalOrder()));
        return node;
    }

    private ArrayNode levels(
            List<PolymarketOrderbookResponse.PriceLevel> levels, Comparator<BigDecimal> priceComparator) {
        ArrayNode array = objectMapper.createArrayNode();
        if (levels == null || levels.isEmpty()) {
            return array;
        }
        levels.stream()
                .filter(level -> level.priceDecimal() != null && level.sizeDecimal() != null)
                .sorted(Comparator.comparing(PolymarketOrderbookResponse.PriceLevel::priceDecimal, priceComparator))
                .limit(STORED_LEVEL_LIMIT)
                .forEach(level -> {
                    ObjectNode node = objectMapper.createObjectNode();
                    putDecimal(node, "price", price(level.priceDecimal()));
                    putDecimal(node, "size", level.sizeDecimal());
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
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Polymarket orderbook snapshot limit must be between 1 and 500");
        }
    }

    public record SnapshotRunResult(int marketsScanned, int snapshotsInserted, int marketsSkipped, int failures) {}

    record SnapshotResult(boolean inserted) {
        static SnapshotResult insertedResult() {
            return new SnapshotResult(true);
        }

        static SnapshotResult skippedResult() {
            return new SnapshotResult(false);
        }
    }

    private static final class MutableSummary {
        private BigDecimal bestYesBid;
        private BigDecimal bestYesAsk;
        private BigDecimal lastYesPrice;
        private BigDecimal bestNoBid;
        private BigDecimal bestNoAsk;
        private BigDecimal lastNoPrice;

        private void accept(String side, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal lastPrice) {
            if (side == null) {
                return;
            }
            switch (side.toUpperCase(Locale.ROOT)) {
                case "YES" -> {
                    bestYesBid = bestBid;
                    bestYesAsk = bestAsk;
                    lastYesPrice = lastPrice;
                }
                case "NO" -> {
                    bestNoBid = bestBid;
                    bestNoAsk = bestAsk;
                    lastNoPrice = lastPrice;
                }
                default -> {
                    // Multi-outcome markets still preserve per-outcome books in JSONB.
                }
            }
        }

        private MarketBookSummary toSummary(int bookCount) {
            return new MarketBookSummary(
                    bookCount,
                    bestYesBid,
                    bestYesAsk,
                    lastYesPrice,
                    bestNoBid,
                    bestNoAsk,
                    lastNoPrice);
        }
    }
}
