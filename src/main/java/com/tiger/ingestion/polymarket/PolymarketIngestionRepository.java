package com.tiger.ingestion.polymarket;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PolymarketIngestionRepository {
    private static final String EXCHANGE = "polymarket";

    private final JdbcClient jdbcClient;

    public PolymarketIngestionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void upsertEventTree(NormalizedEvent event) {
        UUID eventId = upsertEvent(event);
        for (NormalizedMarket market : event.markets()) {
            UUID marketId = upsertMarket(event, eventId, market);
            replaceOutcomes(marketId, market);
        }
    }

    private UUID upsertEvent(NormalizedEvent event) {
        String sql =
                """
                INSERT INTO events (
                    exchange, source_event_id, source_event_ticker, slug, source_series_id,
                    title, description, category, tags, status, source_status,
                    start_at, end_at, market_count, active_market_count, market_questions,
                    total_volume, total_liquidity, open_interest, image_url, icon_url,
                    source_created_at, source_updated_at, discovered_at, raw_payload
                )
                VALUES (
                    :exchange, :source_event_id, :source_event_ticker, :slug, :source_series_id,
                    :title, :description, :category, string_to_array(:tags, chr(31)),
                    :status, CAST(:source_status AS jsonb),
                    :start_at, :end_at, :market_count, :active_market_count,
                    string_to_array(:market_questions, chr(31)),
                    :total_volume, :total_liquidity, :open_interest, :image_url, :icon_url,
                    :source_created_at, :source_updated_at,
                    COALESCE(:source_created_at, now()), CAST(:raw_payload AS jsonb)
                )
                ON CONFLICT (exchange, source_event_id) DO UPDATE SET
                    source_event_ticker = EXCLUDED.source_event_ticker,
                    slug = EXCLUDED.slug,
                    source_series_id = EXCLUDED.source_series_id,
                    title = EXCLUDED.title,
                    description = EXCLUDED.description,
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
                    total_liquidity = EXCLUDED.total_liquidity,
                    open_interest = EXCLUDED.open_interest,
                    image_url = EXCLUDED.image_url,
                    icon_url = EXCLUDED.icon_url,
                    source_created_at = EXCLUDED.source_created_at,
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
                .param("slug", event.slug())
                .param("source_series_id", event.sourceSeriesId())
                .param("title", event.title())
                .param("description", event.description())
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
                .param("total_liquidity", event.totalLiquidity())
                .param("open_interest", event.openInterest())
                .param("image_url", event.imageUrl())
                .param("icon_url", event.iconUrl())
                .param("source_created_at", event.sourceCreatedAt())
                .param("source_updated_at", event.sourceUpdatedAt())
                .param("raw_payload", event.rawPayloadJson())
                .query(UUID.class)
                .single();
    }

    private UUID upsertMarket(NormalizedEvent event, UUID eventId, NormalizedMarket market) {
        String sql =
                """
                INSERT INTO markets (
                    exchange, source_market_id, condition_id, slug, event_id, source_event_id,
                    source_series_id, question, title, description, category, tags, status,
                    source_status, last_yes_price, last_no_price, volume, liquidity,
                    start_at, end_at, open_at, close_at, interval_code, interval_seconds,
                    image_url, icon_url, source_created_at, source_updated_at, discovered_at,
                    raw_payload
                )
                VALUES (
                    :exchange, :source_market_id, :condition_id, :slug, :event_id, :source_event_id,
                    :source_series_id, :question, :title, :description, :category,
                    string_to_array(:tags, chr(31)), :status,
                    CAST(:source_status AS jsonb), :last_yes_price, :last_no_price, :volume, :liquidity,
                    :start_at, :end_at, :open_at, :close_at, :interval_code, :interval_seconds,
                    :image_url, :icon_url, :source_created_at, :source_updated_at,
                    COALESCE(:source_created_at, now()), CAST(:raw_payload AS jsonb)
                )
                ON CONFLICT (exchange, source_market_id) DO UPDATE SET
                    condition_id = EXCLUDED.condition_id,
                    slug = EXCLUDED.slug,
                    event_id = EXCLUDED.event_id,
                    source_event_id = EXCLUDED.source_event_id,
                    source_series_id = EXCLUDED.source_series_id,
                    question = EXCLUDED.question,
                    title = EXCLUDED.title,
                    description = EXCLUDED.description,
                    category = EXCLUDED.category,
                    tags = EXCLUDED.tags,
                    status = EXCLUDED.status,
                    source_status = EXCLUDED.source_status,
                    last_yes_price = EXCLUDED.last_yes_price,
                    last_no_price = EXCLUDED.last_no_price,
                    volume = EXCLUDED.volume,
                    liquidity = EXCLUDED.liquidity,
                    start_at = EXCLUDED.start_at,
                    end_at = EXCLUDED.end_at,
                    open_at = EXCLUDED.open_at,
                    close_at = EXCLUDED.close_at,
                    interval_code = EXCLUDED.interval_code,
                    interval_seconds = EXCLUDED.interval_seconds,
                    image_url = EXCLUDED.image_url,
                    icon_url = EXCLUDED.icon_url,
                    source_created_at = EXCLUDED.source_created_at,
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
                .param("condition_id", market.conditionId())
                .param("slug", market.slug())
                .param("event_id", eventId)
                .param("source_event_id", market.sourceEventId())
                .param("source_series_id", event.sourceSeriesId())
                .param("question", market.question())
                .param("title", market.title())
                .param("description", market.description())
                .param("category", market.category() != null ? market.category() : event.category())
                .param("tags", textArray(merge(event.tags(), market.tags())))
                .param("status", market.status().name())
                .param("source_status", market.sourceStatusJson())
                .param("last_yes_price", market.lastYesPrice())
                .param("last_no_price", market.lastNoPrice())
                .param("volume", market.volume())
                .param("liquidity", market.liquidity())
                .param("start_at", market.startAt())
                .param("end_at", market.endAt())
                .param("open_at", market.openAt())
                .param("close_at", market.closeAt())
                .param("interval_code", market.intervalCode())
                .param("interval_seconds", market.intervalSeconds())
                .param("image_url", market.imageUrl())
                .param("icon_url", market.iconUrl())
                .param("source_created_at", market.sourceCreatedAt())
                .param("source_updated_at", market.sourceUpdatedAt())
                .param("raw_payload", market.rawPayloadJson())
                .query(UUID.class)
                .single();
    }

    private void replaceOutcomes(UUID marketId, NormalizedMarket market) {
        jdbcClient.sql("DELETE FROM market_outcomes WHERE market_id = :market_id")
                .param("market_id", marketId)
                .update();
        for (NormalizedOutcome outcome : market.outcomes()) {
            jdbcClient
                    .sql(
                            """
                            INSERT INTO market_outcomes (
                                market_id, exchange, source_market_id, outcome_key, outcome_name,
                                side, token_id, position, last_price, raw_payload
                            )
                            VALUES (
                                :market_id, :exchange, :source_market_id, :outcome_key, :outcome_name,
                                :side, :token_id, :position, :last_price, '{}'::jsonb
                            )
                            """)
                    .param("market_id", marketId)
                    .param("exchange", EXCHANGE)
                    .param("source_market_id", market.sourceMarketId())
                    .param("outcome_key", outcome.outcomeKey())
                    .param("outcome_name", outcome.outcomeName())
                    .param("side", outcome.side())
                    .param("token_id", outcome.tokenId())
                    .param("position", outcome.position())
                    .param("last_price", outcome.lastPrice())
                    .update();
        }
    }

    private String textArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("\u001F", values);
    }

    private List<String> merge(List<String> first, List<String> second) {
        if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
            return List.of();
        }
        List<String> merged = new java.util.ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            for (String value : second) {
                if (!merged.contains(value)) {
                    merged.add(value);
                }
            }
        }
        return merged;
    }
}
