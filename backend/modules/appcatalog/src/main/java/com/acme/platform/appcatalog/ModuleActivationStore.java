package com.acme.platform.appcatalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 模块激活状态的全部 JDBC 读写。
 *
 * <p>与 {@link AppCatalogStore} 一样，所有方法都假设调用方已处于
 * {@code TenantTransactions.inTenant(...)} 事务中；tenant_id 由 RLS 上下文提供。
 */
@Component
public class ModuleActivationStore {

    private final JdbcTemplate jdbcTemplate;

    public ModuleActivationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ActivationRow(UUID moduleId, String status, String config, long version,
                                OffsetDateTime enabledAt, String enabledBy,
                                OffsetDateTime disabledAt, String disabledBy,
                                OffsetDateTime updatedAt) {
    }

    public record ActivationEventRow(UUID id, String action, String fromStatus, String toStatus,
                                     String actor, String comment, OffsetDateTime occurredAt) {
    }

    private static final String ACTIVATION_COLUMNS = """
            module_id, status, config::text AS config, version, enabled_at, enabled_by,
            disabled_at, disabled_by, updated_at
            """;

    private static ActivationRow activation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ActivationRow(rs.getObject("module_id", UUID.class), rs.getString("status"),
                rs.getString("config"), rs.getLong("version"),
                rs.getObject("enabled_at", OffsetDateTime.class), rs.getString("enabled_by"),
                rs.getObject("disabled_at", OffsetDateTime.class), rs.getString("disabled_by"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public Optional<ActivationRow> find(UUID moduleId) {
        return jdbcTemplate.query("SELECT " + ACTIVATION_COLUMNS + """
                  FROM app_module_activation
                 WHERE module_id = ?
                """, (rs, rowNum) -> activation(rs), moduleId).stream().findFirst();
    }

    public Set<String> disabledCodes() {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT m.code
                  FROM app_module_activation a
                  JOIN app_module m ON m.id = a.module_id
                 WHERE a.status = 'disabled'
                """, String.class));
    }

    /** 仍处于启用状态、且依赖声明里包含给定模块 code 的模块；用于阻止停用被依赖模块。 */
    public List<String> enabledDependents(String code) {
        return jdbcTemplate.queryForList("""
                SELECT m.code
                  FROM app_module m
                  LEFT JOIN app_module_activation a ON a.module_id = m.id
                 WHERE jsonb_exists(m.dependencies, ?)
                   AND COALESCE(a.status, 'enabled') = 'enabled'
                 ORDER BY m.code
                """, String.class, code);
    }

    /** 模块启停审计事件，最新在前；只读追加表，最多返回 50 条。 */
    public List<ActivationEventRow> events(UUID moduleId) {
        return jdbcTemplate.query("""
                SELECT id, action, from_status, to_status, actor, comment, occurred_at
                  FROM app_module_activation_event
                 WHERE module_id = ?
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT 50
                """, (rs, rowNum) -> new ActivationEventRow(
                        rs.getObject("id", UUID.class), rs.getString("action"),
                        rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("actor"), rs.getString("comment"),
                        rs.getObject("occurred_at", OffsetDateTime.class)), moduleId);
    }

    public List<String> moduleCodes() {
        return jdbcTemplate.queryForList("SELECT code FROM app_module ORDER BY code", String.class);
    }

    /** 租户是否处于受管状态：存在任意激活行。未受管租户保持「无行=启用」。 */
    public boolean tenantManaged() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app_module_activation)", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /** 首次写入激活行；调用方必须先确认不存在，否则唯一约束会报错。 */
    public void insert(UUID moduleId, String status, String actor, String comment) {
        String enabledBy = "enabled".equals(status) ? actor : null;
        String disabledBy = "disabled".equals(status) ? actor : null;
        jdbcTemplate.update("""
                INSERT INTO app_module_activation
                    (tenant_id, module_id, status, enabled_at, enabled_by,
                     disabled_at, disabled_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, CASE WHEN ? = 'enabled' THEN now() END, ?,
                        CASE WHEN ? = 'disabled' THEN now() END, ?)
                """, moduleId, status, status, enabledBy, status, disabledBy);
        insertEvent(moduleId, "enabled".equals(status) ? "enable" : "disable", null, status,
                actor, comment);
    }

    /** 乐观锁更新；返回 false 表示版本已被并发修改。 */
    public boolean updateStatus(UUID moduleId, String status, long expectedVersion, String actor,
                                String comment) {
        boolean enabling = "enabled".equals(status);
        int updated;
        if (enabling) {
            updated = jdbcTemplate.update("""
                    UPDATE app_module_activation
                       SET status = 'enabled', enabled_at = now(), enabled_by = ?,
                           disabled_at = NULL, disabled_by = NULL,
                           version = version + 1, updated_at = now()
                     WHERE module_id = ? AND version = ?
                    """, actor, moduleId, expectedVersion);
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE app_module_activation
                       SET status = 'disabled', disabled_at = now(), disabled_by = ?,
                           version = version + 1, updated_at = now()
                     WHERE module_id = ? AND version = ?
                    """, actor, moduleId, expectedVersion);
        }
        if (updated == 1) {
            insertEvent(moduleId, enabling ? "enable" : "disable", null, status, actor, comment);
            return true;
        }
        return false;
    }

    public void insertEvent(UUID moduleId, String action, String fromStatus, String toStatus,
                            String actor, String comment) {
        jdbcTemplate.update("""
                INSERT INTO app_module_activation_event
                    (tenant_id, module_id, action, from_status, to_status, actor, comment)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?)
                """, moduleId, action, fromStatus, toStatus, actor == null ? "" : actor,
                comment == null ? "" : comment);
    }

    /** 新租户基础模块：目录记录 + 启用行；已存在则跳过，保证幂等。 */
    public void insertBaseModule(String code, String name, String actor) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                INSERT INTO app_module
                    (tenant_id, code, name, category, description, version, dependencies,
                     applicable_scope, health_status, install_status)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, '基础', '平台基础模块', '1.0.0', '[]'::jsonb,
                        'tenant', 'healthy', 'installed')
                ON CONFLICT (tenant_id, code) DO NOTHING
                RETURNING id
                """, UUID.class, code, name);
        if (ids.isEmpty()) {
            return;
        }
        insert(ids.get(0), "enabled", actor, "新租户初始化：默认启用基础模块");
    }

    /** 受管租户新增模块默认停用，由管理员按需启用。 */
    public void insertDefaultDisabled(UUID moduleId, String actor) {
        insert(moduleId, "disabled", actor, "新模块默认停用，等待管理员启用");
    }
}
