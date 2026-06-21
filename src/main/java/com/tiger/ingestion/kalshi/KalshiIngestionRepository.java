package com.tiger.ingestion.kalshi;

import com.tiger.domain.CanonicalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KalshiIngestionRepository {
    static final String EXCHANGE = "kalshi";

    private final JdbcClient jdbcClient;

    public KalshiIngestionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public UpsertResult upsertSeries(NormalizedSeries series) {
        boolean existed = findSeriesId(series.sourceSeriesId()).isPresent();
        upsertSeriesRow(series);
        return existed ? UpsertResult.updated() : UpsertResult.inserted();
    }

    @Transactional
    public void upsertEventTree(NormalizedEvent event) {
        if (event.sourceSeriesId() != null) {
            // Ensure series row exists before linking events when series was not preloaded.
            findSeriesId(event.sourceSeriesId()).orElseGet(() -> upsertPlaceholderSeries(event));
        }
        UUID eventId = upsertEvent(event);
        for (NormalizedMarket market : event.markets()) {
            UUID marketId = upsertMarket(event, eventId, market);
            upsertOutcomes(marketId, market);
        }
    }

    @Transactional
    public void upsertMarketBundle(NormalizedMarket market) {
        UUID eventId =
                findEventId(market.sourceEventId()).orElseGet(() -> upsertPlaceholderEvent(market));
        UUID marketId = upsertMarketRow(market, eventId);
        upsertOutcomes(marketId, market);
    }

    public Optional<UUID> findSeriesId(String sourceSeriesId) {
        return jdbcClient
                .sql(
                        """
                        SELECT id FROM series
                        WHERE exchange = :exchange AND source_series_id = :source_series_id
                        """)
                .param("exchange", EXCHANGE)
                .param("source_series_id", sourceSeriesId)
                .query(UUID.class)
                .optional();
    }

    private Optional<UUID> findEventId(String sourceEventId) {
        return jdbcClient
                .sql(
                        """
                        SELECT id FROM events
                        WHERE exchange = :exchange AND source_event_id = :source_event_id
                        """)
                .param("exchange", EXCHANGE)
                .param("source_event_id", sourceEventId)
                .query(UUID.class)
                .optional();
    }

    private UUID upsertPlaceholderEvent(NormalizedMarket market) {
        if (market.sourceSeriesId() != null) {
            findSeriesId(market.sourceSeriesId()).orElseGet(() -> upsertPlaceholderSeries(market));
        }
        NormalizedEvent placeholder =
                new NormalizedEvent(
                        market.sourceEventId(),
                        market.sourceEventId(),
                        market.sourceSeriesId(),
                        market.question(),
                        market.subtitle(),
                        market.category(),
                        List.of(),
                        market.status(),
                        market.sourceStatusJson(),
                        market.openAt(),
                        market.closeAt(),
                        null,
                        null,
                        1,
                        market.status() == CanonicalStatus.active ? 1 : 0,
                        List.of(market.question()),
                        market.volume(),
                        market.sourceUpdatedAt(),
                        "{}",
                        List.of());
        return upsertEvent(placeholder);
    }

    private UUID upsertPlaceholderSeries(NormalizedMarket market) {
        NormalizedSeries placeholder =
                new NormalizedSeries(
                        market.sourceSeriesId(),
                        market.sourceSeriesId(),
                        market.sourceSeriesId(),
                        null,
                        market.category(),
                        List.of(),
                        null,
                        market.status(),
                        market.sourceStatusJson(),
                        null,
                        market.sourceUpdatedAt(),
                        "{}");
        return upsertSeriesRow(placeholder);
    }

    private UUID upsertPlaceholderSeries(NormalizedEvent event) {
        NormalizedSeries placeholder =
                new NormalizedSeries(
                        event.sourceSeriesId(),
                        event.sourceSeriesId(),
                        event.sourceSeriesId(),
                        null,
                        event.category(),
                        List.of(),
                        null,
                        event.status(),
                        event.sourceStatusJson(),
                        null,
                        event.sourceUpdatedAt(),
                        "{}");
        return upsertSeriesRow(placeholder);
    }

    private UUID upsertSeriesRow(NormalizedSeries series) {
        String sql =
                """
                INSERT INTO series (
                    exchange, source_series_id, source_ticker, title, subtitle, category, tags,
                    frequency, status, source_status, total_volume, source_updated_at, raw_payload
                )
                VALUES (
                    :exchange, :source_series_id, :source_ticker, :title, :subtitle, :category,
                    string_to_array(:tags, chr(31)), :frequency, :status,
                    CAST(:source_status AS jsonb), :total_volume, :source_updated_at,
                    CAST(:raw_payload AS jsonb)
                )
                ON CONFLICT (exchange, source_series_id) DO UPDATE SET
                    source_ticker = EXCLUDED.source_ticker,
                    title = EXCLUDED.title,
                    subtitle = EXCLUDED.subtitle,
                    category = EXCLUDED.category,
                    tags = EXCLUDED.tags,
                    frequency = EXCLUDED.frequency,
                    status = EXCLUDED.status,
                    source_status = EXCLUDED.source_status,
                    total_volume = EXCLUDED.total_volume,
                    source_updated_at = EXCLUDED.source_updated_at,
                    last_seen_at = now(),
                    raw_payload = EXCLUDED.raw_payload,
                    updated_at = now()
                RETURNING id
                """;
        return jdbcClient
                .sql(sql)
                .param("exchange", EXCHANGE)
                .param("source_series_id", series.sourceSeriesId())
                .param("source_ticker", series.sourceTicker())
                .param("title", series.title())
                .param("subtitle", series.subtitle())
                .param("category", series.category())
                .param("tags", textArray(series.tags()))
                .param("frequency", series.frequency())
                .param("status", series.status().name())
                .param("source_status", series.sourceStatusJson())
                .param("total_volume", series.totalVolume())
                .param("source_updated_at", series.sourceUpdatedAt())
                .param("raw_payload", series.rawPayloadJson())
                .query(UUID.class)
                .single();
    }

    private UUID upsertEvent(NormalizedEvent event) {
        UUID seriesId =
                event.sourceSeriesId() == null
                        ? null
                        : findSeriesId(event.sourceSeriesId()).orElse(null);
        String sql =
                """
                INSERT INTO events (
                    exchange, source_event_id, source_event_ticker, source_series_id, series_id,
                    title, subtitle, category, tags, status, source_status,
                    start_at, end_at, market_count, active_market_count, market_questions,
                    total_volume, source_updated_at, raw_payload
                )
                VALUES (
                    :exchange, :source_event_id, :source_event_ticker, :source_series_id, :series_id,
                    :title, :subtitle, :category, string_to_array(:tags, chr(31)),
                    :status, CAST(:source_status AS jsonb),
                    :start_at, :end_at, :market_count, :active_market_count,
                    string_to_array(:market_questions, chr(31)),
                    :total_volume, :source_updated_at, CAST(:raw_payload AS jsonb)
                )
                ON CONFLICT (exchange, source_event_id) DO UPDATE SET
                    source_event_ticker = EXCLUDED.source_event_ticker,
                    source_series_id = EXCLUDED.source_series_id,
                    series_id = EXCLUDED.series_id,
                    title = EXCLUDED.title,
                    subtitle = EXCLUDED.subtitle,
                    category = EXCLUDED.category,
                    tags = EXCLUDED.tags,
                    status = EXCLUDED.status,
                    source_status = EXCLUDED.source_status,
                    start_at = EXCLUDED.start_at,
                    end_at = EXCLUDED.end_at,
                    market_count = EXCLUDED.market_count,
                    active_market_count = EXCLUDED.active_market_count,
                    market_questions = EXCLUDED.market_questions,
                    total_volume = EXCLUDED.total_volume,
                    source_updated_at = EXCLUDED.source_updated_at,
                    last_seen_at = now(),
                    raw_payload = EXCLUDED.raw_payload,
                    updated_at = now()
                RETURNING id
                """;
        return jdbcClient
                .sql(sql)
                .param("exchange", EXCHANGE)
                .param("source_event_id", event.sourceEventId())
                .param("source_event_ticker", event.sourceEventTicker())
                .param("source_series_id", event.sourceSeriesId())
                .param("series_id", seriesId)
                .param("title", event.title())
                .param("subtitle", event.subtitle())
                .param("category", event.category())
                .param("tags", textArray(event.tags()))
                .param("status", event.status().name())
                .param("source_status", event.sourceStatusJson())
                .param("start_at", event.startAt())
                .param("end_at", event.endAt())
                .param("market_count", event.marketCount())
                .param("active_market_count", event.activeMarketCount())
                .param("market_questions", textArray(event.marketQuestions()))
                .param("total_volume", event.totalVolume())
                .param("source_updated_at", event.sourceUpdatedAt())
                .param("raw_payload", event.rawPayloadJson())
                .query(UUID.class)
                .single();
    }

    private UUID upsertMarket(NormalizedEvent event, UUID eventId, NormalizedMarket market) {
        return upsertMarketRow(market, eventId, event.sourceSeriesId());
    }

    private UUID upsertMarketRow(NormalizedMarket market, UUID eventId) {
        return upsertMarketRow(market, eventId, market.sourceSeriesId());
    }

    private UUID upsertMarketRow(NormalizedMarket market, UUID eventId, String sourceSeriesId) {
        UUID seriesId =
                sourceSeriesId == null ? null : findSeriesId(sourceSeriesId).orElse(null);
        String sql =
                """
                INSERT INTO markets (
                    exchange, source_market_id, source_market_ticker, event_id, source_event_id,
                    series_id, source_series_id, question, title, subtitle, rules_primary,
                    rules_secondary, category, tags, status, source_status,
                    last_yes_price, last_no_price, best_yes_bid, best_yes_ask, best_no_bid, best_no_ask,
                    volume, volume_24h, open_interest, liquidity, settlement_value,
                    open_at, close_at, settled_at, source_updated_at, raw_payload
                )
                VALUES (
                    :exchange, :source_market_id, :source_market_ticker, :event_id, :source_event_id,
                    :series_id, :source_series_id, :question, :title, :subtitle, :rules_primary,
                    :rules_secondary, :category, string_to_array(:tags, chr(31)), :status,
                    CAST(:source_status AS jsonb), :last_yes_price, :last_no_price,
                    :best_yes_bid, :best_yes_ask, :best_no_bid, :best_no_ask,
                    :volume, :volume_24h, :open_interest, :liquidity, :settlement_value,
                    :open_at, :close_at, :settled_at, :source_updated_at, CAST(:raw_payload AS jsonb)
                )
                ON CONFLICT (exchange, source_market_id) DO UPDATE SET
                    source_market_ticker = EXCLUDED.source_market_ticker,
                    event_id = EXCLUDED.event_id,
                    source_event_id = EXCLUDED.source_event_id,
                    series_id = EXCLUDED.series_id,
                    source_series_id = EXCLUDED.source_series_id,
                    question = EXCLUDED.question,
                    title = EXCLUDED.title,
                    subtitle = EXCLUDED.subtitle,
                    rules_primary = EXCLUDED.rules_primary,
                    rules_secondary = EXCLUDED.rules_secondary,
                    category = EXCLUDED.category,
                    tags = EXCLUDED.tags,
                    status = EXCLUDED.status,
                    source_status = EXCLUDED.source_status,
                    last_yes_price = EXCLUDED.last_yes_price,
                    last_no_price = EXCLUDED.last_no_price,
                    best_yes_bid = EXCLUDED.best_yes_bid,
                    best_yes_ask = EXCLUDED.best_yes_ask,
                    best_no_bid = EXCLUDED.best_no_bid,
                    best_no_ask = EXCLUDED.best_no_ask,
                    volume = EXCLUDED.volume,
                    volume_24h = EXCLUDED.volume_24h,
                    open_interest = EXCLUDED.open_interest,
                    liquidity = EXCLUDED.liquidity,
                    settlement_value = EXCLUDED.settlement_value,
                    open_at = EXCLUDED.open_at,
                    close_at = EXCLUDED.close_at,
                    settled_at = EXCLUDED.settled_at,
                    source_updated_at = EXCLUDED.source_updated_at,
                    last_seen_at = now(),
                    raw_payload = EXCLUDED.raw_payload,
                    updated_at = now()
                RETURNING id
                """;
        return jdbcClient
                .sql(sql)
                .param("exchange", EXCHANGE)
                .param("source_market_id", market.sourceMarketId())
                .param("source_market_ticker", market.sourceMarketTicker())
                .param("event_id", eventId)
                .param("source_event_id", market.sourceEventId())
                .param("series_id", seriesId)
                .param("source_series_id", sourceSeriesId)
                .param("question", market.question())
                .param("title", market.title())
                .param("subtitle", market.subtitle())
                .param("rules_primary", market.rulesPrimary())
                .param("rules_secondary", market.rulesSecondary())
                .param("category", market.category())
                .param("tags", textArray(market.tags()))
                .param("status", market.status().name())
                .param("source_status", market.sourceStatusJson())
                .param("last_yes_price", market.lastYesPrice())
                .param("last_no_price", market.lastNoPrice())
                .param("best_yes_bid", market.bestYesBid())
                .param("best_yes_ask", market.bestYesAsk())
                .param("best_no_bid", market.bestNoBid())
                .param("best_no_ask", market.bestNoAsk())
                .param("volume", market.volume())
                .param("volume_24h", market.volume24h())
                .param("open_interest", market.openInterest())
                .param("liquidity", market.liquidity())
                .param("settlement_value", market.settlementValue())
                .param("open_at", market.openAt())
                .param("close_at", market.closeAt())
                .param("settled_at", market.settledAt())
                .param("source_updated_at", market.sourceUpdatedAt())
                .param("raw_payload", market.rawPayloadJson())
                .query(UUID.class)
                .single();
    }

    private void upsertOutcomes(UUID marketId, NormalizedMarket market) {
        for (NormalizedOutcome outcome : market.outcomes()) {
            jdbcClient
                    .sql(
                            """
                            INSERT INTO market_outcomes (
                                market_id, exchange, source_market_id, outcome_key, outcome_name,
                                side, position, last_price, best_bid, best_ask, settlement_value,
                                raw_payload
                            )
                            VALUES (
                                :market_id, :exchange, :source_market_id, :outcome_key, :outcome_name,
                                :side, :position, :last_price, :best_bid, :best_ask, :settlement_value,
                                CAST(:raw_payload AS jsonb)
                            )
                            ON CONFLICT (market_id, outcome_key) DO UPDATE SET
                                outcome_name = EXCLUDED.outcome_name,
                                side = EXCLUDED.side,
                                position = EXCLUDED.position,
                                last_price = EXCLUDED.last_price,
                                best_bid = EXCLUDED.best_bid,
                                best_ask = EXCLUDED.best_ask,
                                settlement_value = EXCLUDED.settlement_value,
                                raw_payload = EXCLUDED.raw_payload,
                                updated_at = now()
                            """)
                    .param("market_id", marketId)
                    .param("exchange", EXCHANGE)
                    .param("source_market_id", market.sourceMarketId())
                    .param("outcome_key", outcome.outcomeKey())
                    .param("outcome_name", outcome.outcomeName())
                    .param("side", outcome.side())
                    .param("position", outcome.position())
                    .param("last_price", outcome.lastPrice())
                    .param("best_bid", outcome.bestBid())
                    .param("best_ask", outcome.bestAsk())
                    .param("settlement_value", outcome.settlementValue())
                    .param("raw_payload", outcome.rawPayloadJson())
                    .update();
        }
    }

    private String textArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("\u001F", values);
    }

    public enum UpsertResult {
        INSERTED,
        UPDATED;

        static UpsertResult inserted() {
            return INSERTED;
        }

        static UpsertResult updated() {
            return UPDATED;
        }
    }
}
