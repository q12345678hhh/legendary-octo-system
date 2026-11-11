package com.acme.platform.organization;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 组织变更历史：只插入。
 *
 * <p>数据库层还有触发器阻止 UPDATE/DELETE；本类刻意不提供修改方法，
 * 让「历史不可变」在代码与数据库两处都成立。
 */
@Component
public class OrganizationHistoryStore {

    private static final RowMapper<OrganizationHistoryEntry> MAPPER =
            (rs, rowNum) -> new OrganizationHistoryEntry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("change_id", UUID.class),
                    rs.getString("entity_type"),
                    rs.getObject("entity_id", UUID.class),
                    rs.getString("action"),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getString("before_state"),
                    rs.getString("after_state"),
                    rs.getString("actor"),
                    rs.getObject("occurred_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public OrganizationHistoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID changeId, String entityType, UUID entityId, String action,
                       LocalDate effectiveDate, String beforeState, String afterState, String actor) {
        jdbcTemplate.update("""
                INSERT INTO organization_change_history
                    (tenant_id, change_id, entity_type, entity_id, action, effective_date,
                     before_state, after_state, actor)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?,
                        ?::jsonb, ?::jsonb, ?)
                """, changeId, entityType, entityId, action, effectiveDate,
                beforeState, afterState, actor);
    }

    public List<OrganizationHistoryEntry> list(String entityType, UUID entityId) {
        if (entityType == null || entityType.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT id, tenant_id, change_id, entity_type, entity_id, action,
                           effective_date, before_state::text AS before_state,
                           after_state::text AS after_state, actor, occurred_at
                      FROM organization_change_history
                     ORDER BY occurred_at DESC LIMIT 200
                    """, MAPPER);
        }
        return jdbcTemplate.query("""
                SELECT id, tenant_id, change_id, entity_type, entity_id, action,
                       effective_date, before_state::text AS before_state,
                       after_state::text AS after_state, actor, occurred_at
                  FROM organization_change_history
                 WHERE entity_type = ? AND (?::uuid IS NULL OR entity_id = ?::uuid)
                 ORDER BY occurred_at DESC LIMIT 200
                """, MAPPER, entityType, entityId, entityId);
    }
}
