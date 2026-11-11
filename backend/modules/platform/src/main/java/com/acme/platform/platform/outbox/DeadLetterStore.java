package com.acme.platform.platform.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 死信查询与回放。platform_outbox 没有 RLS，所以这里每个语句都显式带 tenant_id，
 * 这是该表跨租户访问的唯一例外面。
 */
@Component
public class DeadLetterStore {

    private static final String LIST_SQL = """
            SELECT event_id, event_name, attempts, last_error, occurred_at, last_attempt_at,
                   correlation_id, trace_id
              FROM platform_outbox
             WHERE tenant_id = ? AND status = 'dead'
             ORDER BY occurred_at DESC
             LIMIT ?
            """;

    private static final String REPLAY_SQL = """
            UPDATE platform_outbox
               SET status = 'pending', attempts = 0, available_at = now(),
                   last_error = NULL, published_at = NULL
             WHERE event_id = ? AND tenant_id = ? AND status = 'dead'
            """;

    private static final String AUDIT_SQL = """
            INSERT INTO platform_outbox_replay (event_id, tenant_id, reason, replayed_by)
            VALUES (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public DeadLetterStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DeadLetterRecord> list(UUID tenantId, int limit) {
        return jdbcTemplate.query(LIST_SQL, (rs, rowNum) -> new DeadLetterRecord(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_name"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getObject("occurred_at", java.time.OffsetDateTime.class),
                rs.getObject("last_attempt_at", java.time.OffsetDateTime.class),
                rs.getString("correlation_id"),
                rs.getString("trace_id")), tenantId, limit);
    }

    /** 返回 false 表示该事件不是本租户的死信或已不在 dead 状态。 */
    public boolean markForReplay(UUID eventId, UUID tenantId) {
        return jdbcTemplate.update(REPLAY_SQL, eventId, tenantId) == 1;
    }

    public void audit(UUID eventId, UUID tenantId, String reason, String actor) {
        jdbcTemplate.update(AUDIT_SQL, eventId, tenantId, reason, actor);
    }
}
