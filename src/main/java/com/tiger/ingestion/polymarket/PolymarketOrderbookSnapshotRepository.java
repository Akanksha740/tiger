package com.tiger.ingestion.polymarket;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PolymarketOrderbookSnapshotRepository {
    private static final String EXCHANGE = "polymarket";

    private final JdbcClient jdbcClient;

    public PolymarketOrderbookSnapshotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<TrackedMarket> findActiveMarketsWithTokenIds(int limit) {
        String sql =
                """
                SELECT m.id AS market_id,
                       m.public_id,
                       m.source_market_id,
                       m.volume,
                       m.liquidity,
                       mo.id AS outcome_id,
                       mo.outcome_key,
                       mo.outcome_name,
                       mo.side,
                       mo.token_id,
                       mo.position
                FROM (
                    SELECT id
                    FROM markets
                    WHERE exchange = :exchange
                      AND status = 'active'
                      AND EXISTS (
                          SELECT 1
                          FROM market_outcomes mo
                          WHERE mo.market_id = markets.id
                            AND mo.token_id IS NOT NULL
                            AND btrim(mo.token_id) <> ''
                      )
                    ORDER BY COALESCE(volume, 0) DESC, discovered_at DESC
                    LIMIT :limit
                ) picked
                JOIN markets m ON m.id = picked.id
                JOIN market_outcomes mo ON mo.market_id = m.id
                WHERE mo.token_id IS NOT NULL
                  AND btrim(mo.token_id) <> ''
                ORDER BY COALESCE(m.volume, 0) DESC, m.source_market_id, mo.position ASC
                """;
        List<TrackedMarketRow> rows = jdbcClient
                .sql(sql)
                .param("exchange", EXCHANGE)
                .param("limit", limit)
                .query(this::mapTrackedMarketRow)
                .list();

        Map<UUID, TrackedMarketBuilder> builders = new LinkedHashMap<>();
        for (TrackedMarketRow row : rows) {
            TrackedMarketBuilder builder = builders.computeIfAbsent(
                    row.marketId(),
                    ignored -> new TrackedMarketBuilder(
                            row.marketId(),
                            row.publicId(),
                            row.sourceMarketId(),
                            row.volume(),
                            row.liquidity()));
            builder.outcomes.add(new TrackedOutcome(
                    row.outcomeId(),
                    row.outcomeKey(),
                    row.outcomeName(),
                    row.side(),
                    row.tokenId(),
                    row.position()));
        }

        return builders.values().stream().map(TrackedMarketBuilder::build).toList();
    }

    @Transactional
    public UUID insertSnapshot(TrackedMarket market, MarketBookSummary summary, String orderbooksJson) {
        String sql =
                """
                INSERT INTO market_orderbook_snapshots (
                    market_id, exchange, source_market_id, captured_at,
                    outcome_count, book_count,
                    best_yes_bid, best_yes_ask, last_yes_price,
                    best_no_bid, best_no_ask, last_no_price,
                    volume, liquidity, orderbooks
                )
                VALUES (
                    :market_id, :exchange, :source_market_id, :captured_at,
                    :outcome_count, :book_count,
                    :best_yes_bid, :best_yes_ask, :last_yes_price,
                    :best_no_bid, :best_no_ask, :last_no_price,
                    :volume, :liquidity, CAST(:orderbooks AS jsonb)
                )
                RETURNING id
                """;
        return jdbcClient
                .sql(sql)
                .param("market_id", market.marketId())
                .param("exchange", EXCHANGE)
                .param("source_market_id", market.sourceMarketId())
                .param("captured_at", OffsetDateTime.now())
                .param("outcome_count", market.outcomes().size())
                .param("book_count", summary.bookCount())
                .param("best_yes_bid", summary.bestYesBid())
                .param("best_yes_ask", summary.bestYesAsk())
                .param("last_yes_price", summary.lastYesPrice())
                .param("best_no_bid", summary.bestNoBid())
                .param("best_no_ask", summary.bestNoAsk())
                .param("last_no_price", summary.lastNoPrice())
                .param("volume", market.volume())
                .param("liquidity", market.liquidity())
                .param("orderbooks", orderbooksJson)
                .query(UUID.class)
                .single();
    }

    @Transactional
    public void updateMarketPrices(UUID marketId, MarketBookSummary summary) {
        String sql =
                """
                UPDATE markets
                SET best_yes_bid = COALESCE(:best_yes_bid, best_yes_bid),
                    best_yes_ask = COALESCE(:best_yes_ask, best_yes_ask),
                    last_yes_price = COALESCE(:last_yes_price, last_yes_price),
                    best_no_bid = COALESCE(:best_no_bid, best_no_bid),
                    best_no_ask = COALESCE(:best_no_ask, best_no_ask),
                    last_no_price = COALESCE(:last_no_price, last_no_price),
                    price_source = 'polymarket_clob',
                    updated_at = now()
                WHERE id = :market_id
                """;
        jdbcClient
                .sql(sql)
                .param("market_id", marketId)
                .param("best_yes_bid", summary.bestYesBid())
                .param("best_yes_ask", summary.bestYesAsk())
                .param("last_yes_price", summary.lastYesPrice())
                .param("best_no_bid", summary.bestNoBid())
                .param("best_no_ask", summary.bestNoAsk())
                .param("last_no_price", summary.lastNoPrice())
                .update();
    }

    @Transactional
    public void updateOutcomePrices(UUID outcomeId, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal lastPrice) {
        String sql =
                """
                UPDATE market_outcomes
                SET best_bid = COALESCE(:best_bid, best_bid),
                    best_ask = COALESCE(:best_ask, best_ask),
                    last_price = COALESCE(:last_price, last_price),
                    updated_at = now()
                WHERE id = :outcome_id
                """;
        jdbcClient
                .sql(sql)
                .param("outcome_id", outcomeId)
                .param("best_bid", bestBid)
                .param("best_ask", bestAsk)
                .param("last_price", lastPrice)
                .update();
    }

    private TrackedMarketRow mapTrackedMarketRow(ResultSet rs, int rowNum) throws SQLException {
        return new TrackedMarketRow(
                rs.getObject("market_id", UUID.class),
                rs.getString("public_id"),
                rs.getString("source_market_id"),
                rs.getBigDecimal("volume"),
                rs.getBigDecimal("liquidity"),
                rs.getObject("outcome_id", UUID.class),
                rs.getString("outcome_key"),
                rs.getString("outcome_name"),
                rs.getString("side"),
                rs.getString("token_id"),
                rs.getInt("position"));
    }

    public record TrackedMarket(
            UUID marketId,
            String publicId,
            String sourceMarketId,
            BigDecimal volume,
            BigDecimal liquidity,
            List<TrackedOutcome> outcomes) {}

    public record TrackedOutcome(
            UUID outcomeId,
            String outcomeKey,
            String outcomeName,
            String side,
            String tokenId,
            int position) {}

    public record MarketBookSummary(
            int bookCount,
            BigDecimal bestYesBid,
            BigDecimal bestYesAsk,
            BigDecimal lastYesPrice,
            BigDecimal bestNoBid,
            BigDecimal bestNoAsk,
            BigDecimal lastNoPrice) {}

    private record TrackedMarketRow(
            UUID marketId,
            String publicId,
            String sourceMarketId,
            BigDecimal volume,
            BigDecimal liquidity,
            UUID outcomeId,
            String outcomeKey,
            String outcomeName,
            String side,
            String tokenId,
            int position) {}

    private static final class TrackedMarketBuilder {
        private final UUID marketId;
        private final String publicId;
        private final String sourceMarketId;
        private final BigDecimal volume;
        private final BigDecimal liquidity;
        private final List<TrackedOutcome> outcomes = new ArrayList<>();

        private TrackedMarketBuilder(
                UUID marketId, String publicId, String sourceMarketId, BigDecimal volume, BigDecimal liquidity) {
            this.marketId = marketId;
            this.publicId = publicId;
            this.sourceMarketId = sourceMarketId;
            this.volume = volume;
            this.liquidity = liquidity;
        }

        private TrackedMarket build() {
            return new TrackedMarket(marketId, publicId, sourceMarketId, volume, liquidity, List.copyOf(outcomes));
        }
    }
}
