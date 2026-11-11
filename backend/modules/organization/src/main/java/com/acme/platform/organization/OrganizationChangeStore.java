package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 未来变更单与到期队列的存储。
 *
 * <p>变更单本身是租户表，必须在租户事务内读写；到期队列是系统级表，
 * 供 Worker 在没有租户上下文时找出「哪个租户有到期变更」。
 */
@Component
public class OrganizationChangeStore {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OrganizationChangeStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private RowMapper<OrganizationChange> mapper() {
        return (rs, rowNum) -> {
        String payload = rs.getString("payload");
        return new OrganizationChange(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                OrganizationChange.ChangeType.fromWire(rs.getString("change_type")),
                rs.getObject("target_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class),
                payload == null ? Map.of() : objectMapper.readValue(payload, PAYLOAD_TYPE),
                OrganizationChange.ChangeStatus.fromWire(rs.getString("status")),
                rs.getString("requested_by"),
                rs.getString("reason"),
                rs.getObject("applied_at", java.time.OffsetDateTime.class),
                rs.getString("failure_reason"),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
        };
    }

    public void insert(UUID id, OrganizationChange.ChangeType changeType, UUID targetId,
                       LocalDate effectiveDate, Map<String, Object> payload,
                       String requestedBy, String reason) {
        jdbcTemplate.update("""
                INSERT INTO organization_change
                    (id, tenant_id, change_type, target_id, effective_date, payload,
                     requested_by, reason)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, changeType.wire(), targetId, effectiveDate,
                objectMapper.writeValueAsString(payload == null ? Map.of() : payload),
                requestedBy, reason);
    }

    public Optional<OrganizationChange> find(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, change_type, target_id, effective_date, payload::text AS payload,
                       status, requested_by, reason, applied_at, failure_reason, created_at
                  FROM organization_change WHERE id = ?
                """, mapper(), id).stream().findFirst();
    }

    /** 应用前锁住变更单，避免同一张单被定时任务和手动请求同时应用。 */
    public Optional<OrganizationChange> lock(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, change_type, target_id, effective_date, payload::text AS payload,
                       status, requested_by, reason, applied_at, failure_reason, created_at
                  FROM organization_change WHERE id = ? FOR UPDATE
                """, mapper(), id).stream().findFirst();
    }

    public List<OrganizationChange> list(OrganizationChange.ChangeStatus status, UUID targetId) {
        String filter = status == null ? "" : " AND status = ?";
        String targetFilter = targetId == null ? "" : " AND target_id = ?";
        List<Object> args = new java.util.ArrayList<>();
        if (status != null) {
            args.add(status.wire());
        }
        if (targetId != null) {
            args.add(targetId);
        }
        return jdbcTemplate.query("""
                SELECT id, tenant_id, change_type, target_id, effective_date, payload::text AS payload,
                       status, requested_by, reason, applied_at, failure_reason, created_at
                  FROM organization_change
                 WHERE 1 = 1
                """ + filter + targetFilter + " ORDER BY created_at DESC LIMIT 200",
                mapper(), args.toArray());
    }

    public int markApplied(UUID id) {
        return jdbcTemplate.update("""
                UPDATE organization_change
                   SET status = 'applied', applied_at = now(), failure_reason = NULL, updated_at = now()
                 WHERE id = ? AND status = 'scheduled'
                """, id);
    }

    public void markFailed(UUID id, String reason) {
        jdbcTemplate.update("""
                UPDATE organization_change
                   SET status = 'failed', failure_reason = ?, updated_at = now()
                 WHERE id = ? AND status = 'scheduled'
                """, reason, id);
    }

    public int cancel(UUID id) {
        return jdbcTemplate.update("""
                UPDATE organization_change SET status = 'cancelled', updated_at = now()
                 WHERE id = ? AND status = 'scheduled'
                """, id);
    }

    // ---------------------------------------------------------------- 到期队列

    public void enqueue(UUID changeId, UUID tenantId, LocalDate effectiveDate) {
        jdbcTemplate.update("""
                INSERT INTO platform_organization_due (change_id, tenant_id, effective_date)
                VALUES (?, ?, ?)
                ON CONFLICT (change_id) DO NOTHING
                """, changeId, tenantId, effectiveDate);
    }

    public void dequeue(UUID changeId) {
        jdbcTemplate.update("DELETE FROM platform_organization_due WHERE change_id = ?", changeId);
    }

    /**
     * 认领一批到期候选：多取一天，交给各租户按本地业务日再判一次，
     * 避免 UTC 服务器日期落后于东八区导致「明明到期却晚一天」。
     */
    @Transactional
    public List<DueChange> claimDue(int batchSize) {
        return jdbcTemplate.query("""
                WITH claimed AS (
                    SELECT change_id
                      FROM platform_organization_due
                     WHERE effective_date <= CURRENT_DATE + 1
                     ORDER BY effective_date
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                ), locked AS (
                    UPDATE platform_organization_due target
                       SET enqueued_at = now()
                      FROM claimed
                     WHERE target.change_id = claimed.change_id
                    RETURNING target.change_id, target.tenant_id, target.effective_date
                )
                SELECT change_id, tenant_id, effective_date FROM locked
                """, (rs, rowNum) -> new DueChange(
                rs.getObject("change_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("effective_date", LocalDate.class)), batchSize);
    }

    /** 到期队列条目：只携带领取所需的最小信息。 */
    public record DueChange(UUID changeId, UUID tenantId, LocalDate effectiveDate) {
    }
}
