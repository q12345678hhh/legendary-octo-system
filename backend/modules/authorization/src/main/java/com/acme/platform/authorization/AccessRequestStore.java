package com.acme.platform.authorization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 权限申请的 JDBC 存储；所有方法都必须在租户事务内执行。 */
@Component
public class AccessRequestStore {

    public record AccessRequestRow(UUID id, UUID requesterActorId, UUID requestedForActorId,
                                   String roleCode, ScopeType scopeType, UUID scopeId,
                                   OffsetDateTime validTo, String reason, String state,
                                   UUID decidedByActorId, OffsetDateTime decidedAt,
                                   String decisionNote, UUID assignmentId,
                                   OffsetDateTime createdAt) {
    }

    private static final RowMapper<AccessRequestRow> MAPPER = (rs, rowNum) -> new AccessRequestRow(
            rs.getObject("id", UUID.class),
            rs.getObject("requester_actor_id", UUID.class),
            rs.getObject("requested_for_actor_id", UUID.class),
            rs.getString("role_code"),
            ScopeType.fromWire(rs.getString("scope_type")),
            rs.getObject("scope_id", UUID.class),
            rs.getObject("valid_to", OffsetDateTime.class),
            rs.getString("reason"),
            rs.getString("state"),
            rs.getObject("decided_by_actor_id", UUID.class),
            rs.getObject("decided_at", OffsetDateTime.class),
            rs.getString("decision_note"),
            rs.getObject("assignment_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT id, requester_actor_id, requested_for_actor_id, role_code, scope_type,
                   scope_id, valid_to, reason, state, decided_by_actor_id, decided_at,
                   decision_note, assignment_id, created_at
              FROM authorization_access_request
            """;

    private final JdbcTemplate jdbcTemplate;

    public AccessRequestStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID id, UUID requesterActorId, UUID requestedForActorId, String roleCode,
                       ScopeType scopeType, UUID scopeId, OffsetDateTime validTo, String reason) {
        jdbcTemplate.update("""
                INSERT INTO authorization_access_request
                    (id, tenant_id, requester_actor_id, requested_for_actor_id, role_code,
                     scope_type, scope_id, valid_to, reason)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?)
                """, id, requesterActorId, requestedForActorId, roleCode, scopeType.wire(),
                scopeId, validTo, reason);
    }

    public Optional<AccessRequestRow> find(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** {@code actorId} 为空表示审批视角，可看全租户。 */
    public List<AccessRequestRow> list(String state, UUID actorId, int limit) {
        if (state == null || state.isBlank()) {
            if (actorId == null) {
                return jdbcTemplate.query(SELECT + " ORDER BY created_at DESC LIMIT ?",
                        MAPPER, limit);
            }
            return jdbcTemplate.query(SELECT + """
                     WHERE requester_actor_id = ? OR requested_for_actor_id = ?
                     ORDER BY created_at DESC LIMIT ?
                    """, MAPPER, actorId, actorId, limit);
        }
        if (actorId == null) {
            return jdbcTemplate.query(SELECT + """
                     WHERE state = ?
                     ORDER BY created_at DESC LIMIT ?
                    """, MAPPER, state, limit);
        }
        return jdbcTemplate.query(SELECT + """
                 WHERE state = ?
                   AND (requester_actor_id = ? OR requested_for_actor_id = ?)
                 ORDER BY created_at DESC LIMIT ?
                """, MAPPER, state, actorId, actorId, limit);
    }

    public int decide(UUID id, String state, UUID decidedBy, String note) {
        return jdbcTemplate.update("""
                UPDATE authorization_access_request
                   SET state = ?, decided_by_actor_id = ?, decided_at = now(),
                       decision_note = ?, updated_at = now()
                 WHERE id = ? AND state = 'pending'
                """, state, decidedBy, note, id);
    }

    public void attachAssignment(UUID id, UUID assignmentId) {
        jdbcTemplate.update("""
                UPDATE authorization_access_request
                   SET assignment_id = ?, updated_at = now()
                 WHERE id = ?
                """, assignmentId, id);
    }
}
