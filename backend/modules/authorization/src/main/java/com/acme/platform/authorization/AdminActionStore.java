package com.acme.platform.authorization;

import com.acme.platform.platform.ActorContextHolder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 管理员操作审计：与决策日志一样只允许插入。
 *
 * <p>所有管理写操作必须在同一租户事务里调用本类，保证业务变更与审计要么同时成功，
 * 要么同时回滚；{@code actor_id} 为空表示系统作业（如季度复核生成）。
 */
@Component
public class AdminActionStore {

    public record AdminActionRow(UUID id, UUID actorId, String action, String targetType,
                                 UUID targetId, String detail, OffsetDateTime occurredAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AdminActionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String action, String targetType, UUID targetId, String detailJson) {
        UUID actorId = ActorContextHolder.current().map(actor -> actor.actorId()).orElse(null);
        jdbcTemplate.update("""
                INSERT INTO authorization_admin_action
                    (tenant_id, actor_id, action, target_type, target_id, detail)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?::jsonb)
                """, actorId, action, targetType, targetId,
                detailJson == null || detailJson.isBlank() ? "{}" : detailJson);
    }

    public List<AdminActionRow> list(int limit) {
        return jdbcTemplate.query("""
                SELECT id, actor_id, action, target_type, target_id, detail::text AS detail,
                       occurred_at
                  FROM authorization_admin_action
                 ORDER BY occurred_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> new AdminActionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("detail"),
                rs.getObject("occurred_at", OffsetDateTime.class)), limit);
    }
}
