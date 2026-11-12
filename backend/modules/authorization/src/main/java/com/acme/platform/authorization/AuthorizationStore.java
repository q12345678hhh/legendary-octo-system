package com.acme.platform.authorization;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 授权模块的 JDBC 存储。
 *
 * <p>所有方法都必须在 {@code TenantTransactions.inTenant(...)} 内执行；
 * 写入租户列一律取当前事务的 {@code app.tenant_id}，不信任请求体里的租户 ID。
 */
@Component
public class AuthorizationStore {

    public record ActorRow(UUID id, String actorType, String status) {
    }

    public record AssignmentRow(UUID id, UUID actorId, UUID memberId, String roleCode,
                                ScopeType scopeType, UUID scopeId, OffsetDateTime validFrom,
                                OffsetDateTime validTo, UUID delegatedFromId,
                                OffsetDateTime revokedAt) {
    }

    public record DenyRow(UUID id, UUID actorId, String roleCode, String permissionCode,
                          String resourceType, UUID resourceId, ScopeType scopeType, UUID scopeId,
                          OffsetDateTime expiresAt) {
    }

    public record FieldPolicyRow(String roleCode, String fieldName, boolean canView,
                                 boolean canEdit, boolean canExport, boolean canUseAi,
                                 String effect) {
    }

    public record ConditionRow(String permissionCode, String name, String condition, String effect,
                               int priority) {
    }

    public record RoleRow(UUID id, String code, String name, String description, String status,
                          OffsetDateTime createdAt) {
    }

    public record DecisionLogRow(UUID id, UUID actorId, String permissionCode, String resourceType,
                                 UUID resourceId, boolean allowed, String reasonCode,
                                 String factors, OffsetDateTime occurredAt) {
    }

    private static final RowMapper<ActorRow> ACTOR_MAPPER = (rs, rowNum) -> new ActorRow(
            rs.getObject("id", UUID.class),
            rs.getString("actor_type"),
            rs.getString("status"));

    private static final RowMapper<AssignmentRow> ASSIGNMENT_MAPPER = (rs, rowNum) ->
            new AssignmentRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("actor_id", UUID.class),
                    rs.getObject("member_id", UUID.class),
                    rs.getString("role_code"),
                    ScopeType.fromWire(rs.getString("scope_type")),
                    rs.getObject("scope_id", UUID.class),
                    rs.getObject("valid_from", OffsetDateTime.class),
                    rs.getObject("valid_to", OffsetDateTime.class),
                    rs.getObject("delegated_from_id", UUID.class),
                    rs.getObject("revoked_at", OffsetDateTime.class));

    private static final RowMapper<DenyRow> DENY_MAPPER = (rs, rowNum) -> new DenyRow(
            rs.getObject("id", UUID.class),
            rs.getObject("actor_id", UUID.class),
            rs.getString("role_code"),
            rs.getString("permission_code"),
            rs.getString("resource_type"),
            rs.getObject("resource_id", UUID.class),
            ScopeType.fromWire(rs.getString("scope_type")),
            rs.getObject("scope_id", UUID.class),
            rs.getObject("expires_at", OffsetDateTime.class));

    private static final RowMapper<FieldPolicyRow> FIELD_MAPPER = (rs, rowNum) ->
            new FieldPolicyRow(
                    rs.getString("role_code"),
                    rs.getString("field_name"),
                    rs.getBoolean("can_view"),
                    rs.getBoolean("can_edit"),
                    rs.getBoolean("can_export"),
                    rs.getBoolean("can_use_ai"),
                    rs.getString("effect"));

    private static final RowMapper<ConditionRow> CONDITION_MAPPER = (rs, rowNum) ->
            new ConditionRow(
                    rs.getString("permission_code"),
                    rs.getString("name"),
                    rs.getString("condition"),
                    rs.getString("effect"),
                    rs.getInt("priority"));

    private static final RowMapper<RoleRow> ROLE_MAPPER = (rs, rowNum) -> new RoleRow(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public AuthorizationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 决策读取

    public Optional<ActorRow> findActor(UUID actorId) {
        return jdbcTemplate.query("""
                SELECT id, actor_type, status FROM identity_actor WHERE id = ?
                """, ACTOR_MAPPER, actorId).stream().findFirst();
    }

    public long currentVersion() {
        List<Long> versions = jdbcTemplate.queryForList("""
                SELECT version FROM authorization_cache_version
                """, Long.class);
        return versions.isEmpty() ? 0L : versions.getFirst();
    }

    public List<AssignmentRow> activeAssignments(UUID actorId, OffsetDateTime now) {
        return jdbcTemplate.query("""
                SELECT id, actor_id, member_id, role_code, scope_type, scope_id,
                       valid_from, valid_to, delegated_from_id, revoked_at
                  FROM authorization_assignment
                 WHERE actor_id = ?
                   AND revoked_at IS NULL
                   AND valid_from <= ?
                   AND (valid_to IS NULL OR valid_to > ?)
                 ORDER BY created_at
                """, ASSIGNMENT_MAPPER, actorId, now, now);
    }

    /** 自定义角色的权限集合；系统角色不查库，由 SystemRoleCatalog 给出。 */
    public Map<String, Set<String>> customRolePermissions(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Map.of();
        }
        String csv = String.join(",", roleCodes);
        List<Map.Entry<String, String>> rows = jdbcTemplate.query("""
                SELECT r.code, rp.permission_code
                  FROM authorization_role r
                  JOIN authorization_role_permission rp
                    ON rp.role_id = r.id AND rp.tenant_id = r.tenant_id
                 WHERE r.status = 'active'
                   AND r.code = ANY (string_to_array(?, ',')::text[])
                """, (rs, rowNum) -> Map.entry(rs.getString("code"), rs.getString("permission_code")),
                csv);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            result.computeIfAbsent(row.getKey(), key -> new LinkedHashSet<>()).add(row.getValue());
        }
        return result;
    }

    public List<DenyRow> activeDenies(UUID actorId, Collection<String> roleCodes,
                                      String permissionCode, OffsetDateTime now) {
        String csv = roleCodes == null || roleCodes.isEmpty() ? "" : String.join(",", roleCodes);
        return jdbcTemplate.query("""
                SELECT id, actor_id, role_code, permission_code, resource_type, resource_id,
                       scope_type, scope_id, expires_at
                  FROM authorization_deny
                 WHERE permission_code = ?
                   AND (expires_at IS NULL OR expires_at > ?)
                   AND (actor_id = ?
                        OR (role_code IS NOT NULL
                            AND role_code = ANY (string_to_array(?, ',')::text[])))
                """, DENY_MAPPER, permissionCode, now, actorId, csv);
    }

    public List<FieldPolicyRow> fieldPolicies(Collection<String> roleCodes, String resourceType) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        String csv = String.join(",", roleCodes);
        return jdbcTemplate.query("""
                SELECT role_code, field_name, can_view, can_edit, can_export, can_use_ai, effect
                  FROM authorization_field_policy
                 WHERE resource_type = ?
                   AND role_code = ANY (string_to_array(?, ',')::text[])
                 ORDER BY role_code, field_name
                """, FIELD_MAPPER, resourceType, csv);
    }

    public List<ConditionRow> conditionPolicies(String permissionCode) {
        return jdbcTemplate.query("""
                SELECT permission_code, name, condition::text AS condition, effect, priority
                  FROM authorization_condition_policy
                 WHERE permission_code = ?
                   AND status = 'active'
                 ORDER BY priority, created_at
                """, CONDITION_MAPPER, permissionCode);
    }

    public void insertDecisionLog(UUID actorId, String permissionCode, String resourceType,
                                  UUID resourceId, boolean allowed, String reasonCode,
                                  String factorsJson) {
        jdbcTemplate.update("""
                INSERT INTO authorization_decision_log
                    (tenant_id, actor_id, permission_code, resource_type, resource_id,
                     allowed, reason_code, factors)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, actorId, permissionCode, resourceType, resourceId, allowed, reasonCode,
                factorsJson);
    }

    public List<DecisionLogRow> decisionLogs(int limit) {
        return jdbcTemplate.query("""
                SELECT id, actor_id, permission_code, resource_type, resource_id,
                       allowed, reason_code, factors::text AS factors, occurred_at
                  FROM authorization_decision_log
                 ORDER BY occurred_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> new DecisionLogRow(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("permission_code"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                rs.getBoolean("allowed"),
                rs.getString("reason_code"),
                rs.getString("factors"),
                rs.getObject("occurred_at", OffsetDateTime.class)), limit);
    }

    // ---------------------------------------------------------------- 角色管理

    public List<RoleRow> listRoles() {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, status, created_at
                  FROM authorization_role ORDER BY code
                """, ROLE_MAPPER);
    }

    public Optional<RoleRow> findRole(UUID roleId) {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, status, created_at
                  FROM authorization_role WHERE id = ?
                """, ROLE_MAPPER, roleId).stream().findFirst();
    }

    public Optional<RoleRow> findRoleByCode(String code) {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, status, created_at
                  FROM authorization_role WHERE code = ?
                """, ROLE_MAPPER, code).stream().findFirst();
    }

    public void insertRole(UUID id, String code, String name, String description) {
        jdbcTemplate.update("""
                INSERT INTO authorization_role
                    (id, tenant_id, code, name, description)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, id, code, name, description);
    }

    public Set<String> rolePermissions(UUID roleId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT permission_code FROM authorization_role_permission WHERE role_id = ?
                """, String.class, roleId));
    }

    public void replaceRolePermissions(UUID roleId, Set<String> permissionCodes) {
        jdbcTemplate.update("DELETE FROM authorization_role_permission WHERE role_id = ?", roleId);
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO authorization_role_permission (tenant_id, role_id, permission_code)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                """, permissionCodes.stream()
                .map(code -> new Object[]{roleId, code})
                .toList());
    }

    // ---------------------------------------------------------------- 授权管理

    public List<AssignmentRow> listAssignments(UUID actorId, int limit) {
        if (actorId == null) {
            return jdbcTemplate.query("""
                    SELECT id, actor_id, member_id, role_code, scope_type, scope_id,
                           valid_from, valid_to, delegated_from_id, revoked_at
                      FROM authorization_assignment
                     ORDER BY created_at DESC
                     LIMIT ?
                    """, ASSIGNMENT_MAPPER, limit);
        }
        return jdbcTemplate.query("""
                SELECT id, actor_id, member_id, role_code, scope_type, scope_id,
                       valid_from, valid_to, delegated_from_id, revoked_at
                  FROM authorization_assignment
                 WHERE actor_id = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """, ASSIGNMENT_MAPPER, actorId, limit);
    }

    public Optional<AssignmentRow> findAssignment(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, actor_id, member_id, role_code, scope_type, scope_id,
                       valid_from, valid_to, delegated_from_id, revoked_at
                  FROM authorization_assignment WHERE id = ?
                """, ASSIGNMENT_MAPPER, id).stream().findFirst();
    }

    public void insertAssignment(UUID id, UUID actorId, UUID memberId, String roleCode,
                                 ScopeType scopeType, UUID scopeId, OffsetDateTime validFrom,
                                 OffsetDateTime validTo, String reason, String grantedBy,
                                 UUID delegatedFromId) {
        jdbcTemplate.update("""
                INSERT INTO authorization_assignment
                    (id, tenant_id, actor_id, member_id, role_code, scope_type, scope_id,
                     valid_from, valid_to, reason, granted_by, delegated_from_id)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actorId, memberId, roleCode, scopeType.wire(), scopeId, validFrom, validTo,
                reason, grantedBy, delegatedFromId);
    }

    public int revokeAssignment(UUID id, String revokedBy) {
        return jdbcTemplate.update("""
                UPDATE authorization_assignment
                   SET revoked_at = now(), revoked_by = ?, updated_at = now()
                 WHERE id = ? AND revoked_at IS NULL
                """, revokedBy, id);
    }

    // ---------------------------------------------------------------- 显式拒绝

    public List<DenyRow> listDenies(int limit) {
        return jdbcTemplate.query("""
                SELECT id, actor_id, role_code, permission_code, resource_type, resource_id,
                       scope_type, scope_id, expires_at
                  FROM authorization_deny ORDER BY created_at DESC LIMIT ?
                """, DENY_MAPPER, limit);
    }

    public Optional<DenyRow> findDeny(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, actor_id, role_code, permission_code, resource_type, resource_id,
                       scope_type, scope_id, expires_at
                  FROM authorization_deny WHERE id = ?
                """, DENY_MAPPER, id).stream().findFirst();
    }

    public void insertDeny(UUID id, UUID actorId, String roleCode, String permissionCode,
                           String resourceType, UUID resourceId, ScopeType scopeType,
                           UUID scopeId, String reason, OffsetDateTime expiresAt,
                           String createdBy) {
        jdbcTemplate.update("""
                INSERT INTO authorization_deny
                    (id, tenant_id, actor_id, role_code, permission_code, resource_type, resource_id,
                     scope_type, scope_id, reason, expires_at, created_by)
                VALUES (?, nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actorId, roleCode, permissionCode, resourceType, resourceId,
                scopeType == null ? null : scopeType.wire(), scopeId, reason, expiresAt, createdBy);
    }

    public int deleteDeny(UUID id) {
        return jdbcTemplate.update("DELETE FROM authorization_deny WHERE id = ?", id);
    }

    // ---------------------------------------------------------------- 字段与条件策略

    public List<FieldPolicyRow> listFieldPolicies() {
        return jdbcTemplate.query("""
                SELECT role_code, field_name, can_view, can_edit, can_export, can_use_ai, effect
                  FROM authorization_field_policy ORDER BY role_code, resource_type, field_name
                """, FIELD_MAPPER);
    }

    public void upsertFieldPolicy(String roleCode, String resourceType, String fieldName,
                                  boolean canView, boolean canEdit, boolean canExport,
                                  boolean canUseAi, String effect) {
        jdbcTemplate.update("""
                INSERT INTO authorization_field_policy
                    (tenant_id, role_code, resource_type, field_name,
                     can_view, can_edit, can_export, can_use_ai, effect)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, role_code, resource_type, field_name)
                DO UPDATE SET can_view = EXCLUDED.can_view,
                              can_edit = EXCLUDED.can_edit,
                              can_export = EXCLUDED.can_export,
                              can_use_ai = EXCLUDED.can_use_ai,
                              effect = EXCLUDED.effect,
                              updated_at = now()
                """, roleCode, resourceType, fieldName, canView, canEdit, canExport, canUseAi,
                effect);
    }

    public List<ConditionRow> listConditionPolicies() {
        return jdbcTemplate.query("""
                SELECT permission_code, name, condition::text AS condition, effect, priority
                  FROM authorization_condition_policy ORDER BY permission_code, priority
                """, CONDITION_MAPPER);
    }

    public void upsertConditionPolicy(String permissionCode, String name, String conditionJson,
                                      String effect, int priority) {
        jdbcTemplate.update("""
                DELETE FROM authorization_condition_policy
                 WHERE permission_code = ? AND name = ?
                """, permissionCode, name);
        jdbcTemplate.update("""
                INSERT INTO authorization_condition_policy
                    (tenant_id, permission_code, name, condition, effect, priority)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?::jsonb, ?, ?)
                """, permissionCode, name, conditionJson, effect, priority);
    }
}
