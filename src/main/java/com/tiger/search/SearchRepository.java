package com.tiger.search;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {
    private final JdbcClient jdbcClient;

    public SearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SeriesResponse> searchSeries(SearchParameters parameters) {
        String sql =
                """
                SELECT public_id AS series_id,
                       exchange,
                       source_series_id,
                       title,
                       category,
                       status,
                       interval_code,
                       n_events_total,
                       n_events_active,
                       total_volume,
                       total_liquidity,
                       earliest_event_at,
                       latest_event_at,
                       discovered_at
                FROM series
                WHERE status = COALESCE(CAST(:status AS text), 'active')
                  AND (CAST(:exchange AS text) IS NULL OR exchange = :exchange)
                  AND (CAST(:category AS text) IS NULL OR category = :category)
                  AND (
                        CAST(:q AS text) IS NULL
                        OR source_series_id ILIKE '%' || :q || '%'
                        OR title ILIKE '%' || :q || '%'
                        OR subtitle ILIKE '%' || :q || '%'
                        OR lower(interval_code) = lower(:q)
                        OR lower(:q) = ANY(aliases)
                  )
                ORDER BY
                  CASE WHEN CAST(:q AS text) IS NOT NULL AND lower(interval_code) = lower(:q) THEN 100 ELSE 0 END DESC,
                  CASE WHEN CAST(:q AS text) IS NOT NULL AND lower(source_series_id) = lower(:q) THEN 80 ELSE 0 END DESC,
                  n_events_active DESC,
                  total_volume DESC NULLS LAST,
                  title ASC
                LIMIT :limit OFFSET :offset
                """;
        return bindBase(sql, parameters).query(this::mapSeries).list();
    }

    public List<EventResponse> searchEvents(SearchParameters parameters) {
        String sql =
                """
                SELECT e.public_id AS event_id,
                       e.exchange,
                       e.source_event_id,
                       e.source_series_id,
                       e.title,
                       e.category,
                       e.status,
                       e.market_count,
                       e.total_volume,
                       e.total_liquidity,
                       e.market_questions,
                       e.start_at,
                       e.end_at,
                       e.discovered_at
                FROM events e
                WHERE e.status = COALESCE(CAST(:status AS text), 'active')
                  AND (CAST(:exchange AS text) IS NULL OR e.exchange = :exchange)
                  AND (CAST(:category AS text) IS NULL OR e.category = :category)
                  AND (CAST(:min_volume AS numeric) IS NULL OR e.total_volume >= :min_volume)
                  AND (CAST(:discovered_after AS timestamptz) IS NULL OR e.discovered_at >= :discovered_after)
                  AND (
                        CAST(:series_id AS text) IS NULL
                        OR e.source_series_id = :series_id
                        OR e.series_id = (
                            SELECT id
                            FROM series
                            WHERE public_id = :series_id OR source_series_id = :series_id
                            LIMIT 1
                        )
                  )
                  AND (
                        CAST(:q AS text) IS NULL
                        OR e.title ILIKE '%' || :q || '%'
                        OR e.subtitle ILIKE '%' || :q || '%'
                        OR e.source_event_id ILIKE '%' || :q || '%'
                        OR EXISTS (
                            SELECT 1
                            FROM unnest(e.market_questions) AS mq(question)
                            WHERE mq.question ILIKE '%' || :q || '%'
                        )
                  )
                ORDER BY
                  CASE WHEN CAST(:q AS text) IS NOT NULL AND lower(e.source_event_id) = lower(:q) THEN 100 ELSE 0 END DESC,
                  CASE WHEN e.status = 'active' THEN 20 ELSE 0 END DESC,
                  e.end_at ASC NULLS LAST,
                  e.total_volume DESC NULLS LAST,
                  e.discovered_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return bindBase(sql, parameters)
                .param("min_volume", parameters.minVolume())
                .param("discovered_after", parameters.discoveredAfter())
                .query(this::mapEvent)
                .list();
    }

    public List<MarketResponse> searchMarkets(SearchParameters parameters) {
        String sql =
                """
                SELECT m.public_id AS market_id,
                       m.exchange,
                       m.source_market_id,
                       m.source_event_id,
                       m.source_series_id,
                       m.question,
                       m.category,
                       m.status,
                       m.last_yes_price,
                       m.last_no_price,
                       m.volume,
                       m.liquidity,
                       m.open_interest,
                       m.discovered_at,
                       m.settled_at
                FROM markets m
                WHERE m.status = COALESCE(CAST(:status AS text), 'active')
                  AND (CAST(:exchange AS text) IS NULL OR m.exchange = :exchange)
                  AND (CAST(:category AS text) IS NULL OR m.category = :category)
                  AND (CAST(:min_volume AS numeric) IS NULL OR m.volume >= :min_volume)
                  AND (CAST(:min_liquidity AS numeric) IS NULL OR m.liquidity >= :min_liquidity)
                  AND (CAST(:settled_after AS timestamptz) IS NULL OR m.settled_at >= :settled_after)
                  AND (CAST(:settled_before AS timestamptz) IS NULL OR m.settled_at <= :settled_before)
                  AND (
                        CAST(:series_id AS text) IS NULL
                        OR m.source_series_id = :series_id
                        OR m.series_id = (
                            SELECT id
                            FROM series
                            WHERE public_id = :series_id OR source_series_id = :series_id
                            LIMIT 1
                        )
                  )
                  AND (
                        CAST(:q AS text) IS NULL
                        OR m.question ILIKE '%' || :q || '%'
                        OR m.title ILIKE '%' || :q || '%'
                        OR m.subtitle ILIKE '%' || :q || '%'
                        OR m.rules_primary ILIKE '%' || :q || '%'
                        OR m.source_market_id ILIKE '%' || :q || '%'
                        OR m.slug ILIKE '%' || :q || '%'
                        OR lower(m.interval_code) = lower(:q)
                  )
                ORDER BY
                  CASE WHEN CAST(:q AS text) IS NOT NULL AND lower(m.source_market_id) = lower(:q) THEN 100 ELSE 0 END DESC,
                  CASE WHEN CAST(:q AS text) IS NOT NULL AND lower(m.interval_code) = lower(:q) THEN 80 ELSE 0 END DESC,
                  CASE WHEN m.status = 'active' THEN 20 ELSE 0 END DESC,
                  ln(COALESCE(m.volume, 0) + 1) * 0.5 DESC,
                  ln(COALESCE(m.liquidity, 0) + 1) * 0.3 DESC,
                  m.discovered_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return bindMarket(sql, parameters).query(this::mapMarket).list();
    }

    public List<EventResponse> recentEvents(SearchParameters parameters) {
        String sql =
                """
                SELECT public_id AS event_id,
                       exchange,
                       source_event_id,
                       source_series_id,
                       title,
                       category,
                       status,
                       market_count,
                       total_volume,
                       total_liquidity,
                       market_questions,
                       start_at,
                       end_at,
                       discovered_at
                FROM events
                WHERE (CAST(:exchange AS text) IS NULL OR exchange = :exchange)
                  AND (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY discovered_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return bindBase(sql, parameters).query(this::mapEvent).list();
    }

    public List<MarketResponse> recentMarkets(SearchParameters parameters) {
        String sql =
                """
                SELECT public_id AS market_id,
                       exchange,
                       source_market_id,
                       source_event_id,
                       source_series_id,
                       question,
                       category,
                       status,
                       last_yes_price,
                       last_no_price,
                       volume,
                       liquidity,
                       open_interest,
                       discovered_at,
                       settled_at
                FROM markets
                WHERE (CAST(:exchange AS text) IS NULL OR exchange = :exchange)
                  AND (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY discovered_at DESC
                LIMIT :limit OFFSET :offset
                """;
        return bindBase(sql, parameters).query(this::mapMarket).list();
    }

    public List<MarketResponse> marketsForEvent(
            String eventId, String status, int limit, int offset) {
        String sql =
                """
                SELECT m.public_id AS market_id,
                       m.exchange,
                       m.source_market_id,
                       m.source_event_id,
                       m.source_series_id,
                       m.question,
                       m.category,
                       m.status,
                       m.last_yes_price,
                       m.last_no_price,
                       m.volume,
                       m.liquidity,
                       m.open_interest,
                       m.discovered_at,
                       m.settled_at
                FROM markets m
                JOIN events e ON e.id = m.event_id
                WHERE (e.public_id = :event_id OR e.source_event_id = :event_id)
                  AND (CAST(:status AS text) IS NULL OR m.status = :status)
                ORDER BY m.volume DESC NULLS LAST, m.question ASC
                LIMIT :limit OFFSET :offset
                """;
        return jdbcClient
                .sql(sql)
                .param("event_id", eventId)
                .param("status", status)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapMarket)
                .list();
    }

    private JdbcClient.StatementSpec bindBase(String sql, SearchParameters parameters) {
        return jdbcClient
                .sql(sql)
                .param("q", parameters.q())
                .param("exchange", parameters.exchange())
                .param("category", parameters.category())
                .param("status", parameters.status())
                .param("series_id", parameters.seriesId())
                .param("limit", parameters.limit())
                .param("offset", parameters.offset());
    }

    private JdbcClient.StatementSpec bindMarket(String sql, SearchParameters parameters) {
        return bindBase(sql, parameters)
                .param("min_volume", parameters.minVolume())
                .param("min_liquidity", parameters.minLiquidity())
                .param("settled_after", parameters.settledAfter())
                .param("settled_before", parameters.settledBefore());
    }

    private SeriesResponse mapSeries(ResultSet rs, int rowNum) throws SQLException {
        return new SeriesResponse(
                rs.getString("series_id"),
                rs.getString("exchange"),
                rs.getString("source_series_id"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("status"),
                rs.getString("interval_code"),
                rs.getObject("n_events_total", Integer.class),
                rs.getObject("n_events_active", Integer.class),
                rs.getBigDecimal("total_volume"),
                rs.getBigDecimal("total_liquidity"),
                getOffsetDateTime(rs, "earliest_event_at"),
                getOffsetDateTime(rs, "latest_event_at"),
                getOffsetDateTime(rs, "discovered_at"));
    }

    private EventResponse mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new EventResponse(
                rs.getString("event_id"),
                rs.getString("exchange"),
                rs.getString("source_event_id"),
                rs.getString("source_series_id"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("status"),
                rs.getObject("market_count", Integer.class),
                rs.getBigDecimal("total_volume"),
                rs.getBigDecimal("total_liquidity"),
                textArray(rs.getArray("market_questions")),
                getOffsetDateTime(rs, "start_at"),
                getOffsetDateTime(rs, "end_at"),
                getOffsetDateTime(rs, "discovered_at"));
    }

    private MarketResponse mapMarket(ResultSet rs, int rowNum) throws SQLException {
        return new MarketResponse(
                rs.getString("market_id"),
                rs.getString("exchange"),
                rs.getString("source_market_id"),
                rs.getString("source_event_id"),
                rs.getString("source_series_id"),
                rs.getString("question"),
                rs.getString("category"),
                rs.getString("status"),
                rs.getBigDecimal("last_yes_price"),
                rs.getBigDecimal("last_no_price"),
                rs.getBigDecimal("volume"),
                rs.getBigDecimal("liquidity"),
                rs.getBigDecimal("open_interest"),
                getOffsetDateTime(rs, "discovered_at"),
                getOffsetDateTime(rs, "settled_at"));
    }

    private static OffsetDateTime getOffsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<String> result = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value != null) {
                result.add(value.toString());
            }
        }
        return result;
    }
}
