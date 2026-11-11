package com.acme.platform.worker.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 {@code platform_outbox} 表的存储实现。
 */
@Repository
class JdbcOutboxStore implements OutboxStore {

    /**
     * 先锁行再更新：子查询里 {@code FOR UPDATE SKIP LOCKED} 拿到的是未被其他 Worker 占用的行，
     * 其余 Worker 会直接跳过，不会互相等待。
     */
    private static final String CLAIM_SQL = """
            WITH claimed AS (
                SELECT event_id
                  FROM platform_outbox
                 WHERE status IN ('pending', 'failed')
                   AND available_at <= now()
                 ORDER BY available_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
            )
            UPDATE platform_outbox target
               SET status = 'processing',
                   attempts = target.attempts + 1
              FROM claimed
             WHERE target.event_id = claimed.event_id
            RETURNING target.event_id,
                      target.event_name,
                      target.schema_version,
                      target.tenant_id,
                      target.correlation_id,
                      target.trace_id,
                      target.producer,
                      target.occurred_at,
                      target.payload,
                      target.attempts
            """;

    private static final String PUBLISHED_SQL = """
            UPDATE platform_outbox
               SET status = 'published',
                   published_at = now(),
                   last_error = NULL
             WHERE event_id = ?
            """;

    private static final String FAILED_SQL = """
            UPDATE platform_outbox
               SET status = CASE WHEN attempts >= ? THEN 'dead' ELSE 'failed' END,
                   last_error = ?,
                   available_at = ?
             WHERE event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    JdbcOutboxStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public List<OutboxEvent> claim(int batchSize) {
        return jdbcTemplate.query(CLAIM_SQL, (rs, rowNum) -> new OutboxEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_name"),
                rs.getInt("schema_version"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("correlation_id"),
                rs.getString("trace_id"),
                rs.getString("producer"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("payload"),
                rs.getInt("attempts")), batchSize);
    }

    @Override
    public void markPublished(UUID eventId) {
        jdbcTemplate.update(PUBLISHED_SQL, eventId);
    }

    @Override
    public void markFailed(UUID eventId, String error, int maxAttempts, Duration retryDelay) {
        jdbcTemplate.update(FAILED_SQL,
                maxAttempts,
                error,
                OffsetDateTime.now().plus(retryDelay),
                eventId);
    }
}
