package com.tiger.ingestion.kalshi;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KalshiIngestionStateRepository {
    static final String EXCHANGE = "kalshi";
    static final String SOURCE_NAME = "kalshi_trade_api";

    private final JdbcClient jdbcClient;

    public KalshiIngestionStateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public UUID startRun(String entityType) {
        return jdbcClient
                .sql(
                        """
                        INSERT INTO ingestion_runs (
                            exchange, source_name, entity_type, status
                        )
                        VALUES (:exchange, :source_name, :entity_type, 'running')
                        RETURNING id
                        """)
                .param("exchange", EXCHANGE)
                .param("source_name", SOURCE_NAME)
                .param("entity_type", entityType)
                .query(UUID.class)
                .single();
    }

    public void finishRun(
            UUID runId,
            String status,
            int fetched,
            int inserted,
            int updated,
            int failed,
            String cursorAfter,
            String errorMessage) {
        jdbcClient
                .sql(
                        """
                        UPDATE ingestion_runs SET
                            finished_at = now(),
                            status = :status,
                            fetched_count = :fetched,
                            inserted_count = :inserted,
                            updated_count = :updated,
                            failed_count = :failed,
                            cursor_after = :cursor_after,
                            error_message = :error_message
                        WHERE id = :id
                        """)
                .param("id", runId)
                .param("status", status)
                .param("fetched", fetched)
                .param("inserted", inserted)
                .param("updated", updated)
                .param("failed", failed)
                .param("cursor_after", cursorAfter)
                .param("error_message", errorMessage)
                .update();
    }

    public void markAttempt(String entityType, String endpoint) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO ingestion_state (
                            exchange, source_name, entity_type, endpoint, last_attempt_at, status
                        )
                        VALUES (:exchange, :source_name, :entity_type, :endpoint, now(), 'unknown')
                        ON CONFLICT (exchange, source_name, entity_type) DO UPDATE SET
                            endpoint = EXCLUDED.endpoint,
                            last_attempt_at = now(),
                            updated_at = now()
                        """)
                .param("exchange", EXCHANGE)
                .param("source_name", SOURCE_NAME)
                .param("entity_type", entityType)
                .param("endpoint", endpoint)
                .update();
    }

    public void markSuccess(String entityType, OffsetDateTime lastSourceUpdatedAt, String cursor) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO ingestion_state (
                            exchange, source_name, entity_type, last_success_at, last_attempt_at,
                            last_source_updated_at, cursor_value, status, error_count, last_error
                        )
                        VALUES (
                            :exchange, :source_name, :entity_type, now(), now(),
                            :last_source_updated_at, :cursor, 'healthy', 0, NULL
                        )
                        ON CONFLICT (exchange, source_name, entity_type) DO UPDATE SET
                            last_success_at = now(),
                            last_attempt_at = now(),
                            last_source_updated_at = COALESCE(
                                EXCLUDED.last_source_updated_at, ingestion_state.last_source_updated_at),
                            cursor_value = COALESCE(EXCLUDED.cursor_value, ingestion_state.cursor_value),
                            status = 'healthy',
                            error_count = 0,
                            last_error = NULL,
                            updated_at = now()
                        """)
                .param("exchange", EXCHANGE)
                .param("source_name", SOURCE_NAME)
                .param("entity_type", entityType)
                .param("last_source_updated_at", lastSourceUpdatedAt)
                .param("cursor", cursor)
                .update();
    }

    public void markFailure(String entityType, String error) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO ingestion_state (
                            exchange, source_name, entity_type, last_attempt_at, status, error_count, last_error
                        )
                        VALUES (:exchange, :source_name, :entity_type, now(), 'failed', 1, :error)
                        ON CONFLICT (exchange, source_name, entity_type) DO UPDATE SET
                            last_attempt_at = now(),
                            status = 'failed',
                            error_count = ingestion_state.error_count + 1,
                            last_error = :error,
                            updated_at = now()
                        """)
                .param("exchange", EXCHANGE)
                .param("source_name", SOURCE_NAME)
                .param("entity_type", entityType)
                .param("error", truncate(error))
                .update();
    }

    public Optional<OffsetDateTime> lastSourceUpdatedAt(String entityType) {
        return jdbcClient
                .sql(
                        """
                        SELECT last_source_updated_at
                        FROM ingestion_state
                        WHERE exchange = :exchange
                          AND source_name = :source_name
                          AND entity_type = :entity_type
                        """)
                .param("exchange", EXCHANGE)
                .param("source_name", SOURCE_NAME)
                .param("entity_type", entityType)
                .query(OffsetDateTime.class)
                .optional();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }
}
