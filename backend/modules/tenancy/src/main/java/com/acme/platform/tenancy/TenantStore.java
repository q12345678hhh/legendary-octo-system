package com.acme.platform.tenancy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 租户生命周期的 JDBC 存储。
 *
 * <p>本类只负责 SQL，不负责事务：调用方必须在 {@code TenantTransactions.inTenant(...)} 内执行，
 * 否则 RLS 会让读写返回 0 行或直接拒绝。
 */
@Component
public class TenantStore {

    private static final RowMapper<TenantRecord> TENANT_MAPPER = (rs, rowNum) -> new TenantRecord(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            TenantStatus.fromWire(rs.getString("status")),
            rs.getString("plan_code"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("frozen_at", OffsetDateTime.class),
            rs.getString("frozen_reason"),
            rs.getObject("closing_requested_at", OffsetDateTime.class),
            rs.getObject("cooling_off_until", OffsetDateTime.class),
            rs.getObject("deleted_at", OffsetDateTime.class));

    private static final RowMapper<TenantDomain> DOMAIN_MAPPER = (rs, rowNum) -> new TenantDomain(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("domain"),
            rs.getString("kind"),
            rs.getBoolean("verified"));

    private static final RowMapper<TenantQuota> QUOTA_MAPPER = (rs, rowNum) -> new TenantQuota(
            rs.getString("quota_key"),
            rs.getLong("limit_value"),
            rs.getLong("used_value"),
            rs.getString("unit"),
            rs.getString("period"));

    private static final RowMapper<TenantLifecycleAudit> AUDIT_MAPPER = (rs, rowNum) -> new TenantLifecycleAudit(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("action"),
            rs.getString("from_status") == null ? null : TenantStatus.fromWire(rs.getString("from_status")),
            TenantStatus.fromWire(rs.getString("to_status")),
            rs.getString("actor"),
            rs.getString("reason"),
            rs.getString("ticket_id"),
            rs.getString("correlation_id"),
            rs.getString("trace_id"),
            rs.getObject("occurred_at", OffsetDateTime.class));

    private static final RowMapper<TenantAppeal> APPEAL_MAPPER = (rs, rowNum) -> new TenantAppeal(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("subject_id"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getObject("submitted_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 平台全局限制：从非 RLS 的 platform_setting 读取，缺失或非法时回退默认值。
     * 两个限制都只影响平台创建租户与支持访问时限，不改变租户内部权限。
     */
    public int maxTenants() {
        return intSetting("limits.max_tenants", 20);
    }

    public int supportAccessMaxMinutes() {
        return intSetting("limits.support_access_max_minutes", 60);
    }

    public long countDirectory() {
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_tenant_directory", Long.class);
        return total == null ? 0 : total;
    }

    private int intSetting(String key, int fallback) {
        return jdbcTemplate.query("""
                SELECT (value #>> '{}')::int AS v FROM platform_setting WHERE setting_key = ?
                """, (rs, rowNum) -> rs.getInt("v"), key).stream()
                .filter(value -> value > 0)
                .findFirst()
                .orElse(fallback);
    }

    public TenantStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<TenantRecord> find(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT id, slug, name, status, plan_code, created_at, updated_at,
                       frozen_at, frozen_reason, closing_requested_at, cooling_off_until, deleted_at
                  FROM tenancy_tenant
                 WHERE id = ?
                """, TENANT_MAPPER, tenantId).stream().findFirst();
    }

    public void insertTenant(UUID tenantId, String slug, String name, String planCode) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
                VALUES (?, ?, ?, 'provisioning', ?)
                """, tenantId, slug, name, planCode);
    }

    public Optional<TenantPlan> findPlan(String code) {
        return jdbcTemplate.query("""
                SELECT code, name, quotas::text AS quotas, features::text AS features
                  FROM tenancy_plan
                 WHERE code = ?
                """, (rs, rowNum) -> new TenantPlan(
                        rs.getString("code"),
                        rs.getString("name"),
                        objectMapper.readValue(rs.getString("quotas"),
                                new TypeReference<Map<String, Long>>() {
                                }),
                        objectMapper.readValue(rs.getString("features"),
                                new TypeReference<Map<String, Boolean>>() {
                                })),
                code).stream().findFirst();
    }

    public void insertProfile(UUID tenantId, String brandName, String brandColor, String locale,
                              String timezone, Map<String, Boolean> features, String updatedBy) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_profile
                    (tenant_id, brand_name, brand_color, locale, timezone, features, updated_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, tenantId, brandName, brandColor, locale, timezone, json(features), updatedBy);
    }

    public void updateProfile(UUID tenantId, String brandName, String brandColor, String locale,
                              String timezone, Map<String, Boolean> features, String updatedBy) {
        jdbcTemplate.update("""
                UPDATE tenancy_profile
                   SET brand_name = ?, brand_color = ?, locale = ?, timezone = ?,
                       features = ?::jsonb, updated_at = now(), updated_by = ?
                 WHERE tenant_id = ?
                """, brandName, brandColor, locale, timezone, json(features), updatedBy, tenantId);
    }

    public Optional<TenantProfile> findProfile(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, brand_name, brand_color, locale, timezone, features::text AS features,
                       updated_at, updated_by
                  FROM tenancy_profile
                 WHERE tenant_id = ?
                """, (rs, rowNum) -> new TenantProfile(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("brand_name"),
                        rs.getString("brand_color"),
                        rs.getString("locale"),
                        rs.getString("timezone"),
                        objectMapper.readValue(rs.getString("features"),
                                new TypeReference<Map<String, Boolean>>() {
                                }),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getString("updated_by")),
                tenantId).stream().findFirst();
    }

    public void insertDomain(UUID tenantId, String domain, String kind) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_domain (tenant_id, domain, kind)
                VALUES (?, ?, ?)
                """, tenantId, domain, kind);
    }

    public List<TenantDomain> findDomains(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, domain, kind, verified
                  FROM tenancy_domain
                 WHERE tenant_id = ?
                 ORDER BY kind, domain
                """, DOMAIN_MAPPER, tenantId);
    }

    public void deleteDomains(UUID tenantId) {
        jdbcTemplate.update("DELETE FROM tenancy_domain WHERE tenant_id = ?", tenantId);
    }

    public void insertQuota(UUID tenantId, String key, long limit, String unit, String period) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_quota (tenant_id, quota_key, limit_value, unit, period)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, key, limit, unit, period);
    }

    public List<TenantQuota> findQuotas(UUID tenantId) {
        return jdbcTemplate.query("""
                SELECT quota_key, limit_value, used_value, unit, period
                  FROM tenancy_quota
                 WHERE tenant_id = ?
                 ORDER BY quota_key
                """, QUOTA_MAPPER, tenantId);
    }

    /** 带条件的原子扣减：并发下不会超过上限，返回 false 表示余额不足。 */
    public boolean consumeQuota(UUID tenantId, String key, long amount) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_quota
                   SET used_value = used_value + ?, updated_at = now()
                 WHERE tenant_id = ? AND quota_key = ? AND used_value + ? <= limit_value
                """, amount, tenantId, key, amount);
        return updated == 1;
    }

    public boolean markInitialized(UUID tenantId) {
        return updateStatus(tenantId, "provisioning", "active");
    }

    public boolean markFrozen(UUID tenantId, String reason, OffsetDateTime frozenAt) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_tenant
                   SET status = 'frozen', frozen_at = ?, frozen_reason = ?,
                       updated_at = now(), version = version + 1
                 WHERE id = ? AND status = 'active'
                """, frozenAt, reason, tenantId);
        return updated == 1;
    }

    public boolean markRestored(UUID tenantId) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_tenant
                   SET status = 'active', frozen_at = NULL, frozen_reason = NULL,
                       updated_at = now(), version = version + 1
                 WHERE id = ? AND status = 'frozen'
                """, tenantId);
        return updated == 1;
    }

    public boolean markClosing(UUID tenantId, OffsetDateTime requestedAt, OffsetDateTime coolingOffUntil) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_tenant
                   SET status = 'closing', closing_requested_at = ?, cooling_off_until = ?,
                       updated_at = now(), version = version + 1
                 WHERE id = ? AND status IN ('active', 'frozen')
                """, requestedAt, coolingOffUntil, tenantId);
        return updated == 1;
    }

    public boolean markClosingCancelled(UUID tenantId) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_tenant
                   SET status = 'active', closing_requested_at = NULL, cooling_off_until = NULL,
                       updated_at = now(), version = version + 1
                 WHERE id = ? AND status = 'closing'
                """, tenantId);
        return updated == 1;
    }

    public boolean markDeleted(UUID tenantId, OffsetDateTime deletedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_tenant
                   SET status = 'deleted', deleted_at = ?, updated_at = now(), version = version + 1
                 WHERE id = ? AND status = 'closing'
                """, deletedAt, tenantId);
        return updated == 1;
    }

    public long nextBusinessSequence(UUID tenantId, String scope) {
        Long next = jdbcTemplate.queryForObject("""
                INSERT INTO tenancy_business_number_sequence (tenant_id, scope, next_value)
                VALUES (?, ?, 1)
                ON CONFLICT (tenant_id, scope)
                DO UPDATE SET next_value = tenancy_business_number_sequence.next_value + 1
                RETURNING next_value
                """, Long.class, tenantId, scope);
        return next == null ? 1 : next;
    }

    public void insertBusinessNumber(UUID tenantId, String scope, String businessNumber, long sequence) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_business_number (tenant_id, scope, business_number, sequence)
                VALUES (?, ?, ?, ?)
                """, tenantId, scope, businessNumber, sequence);
    }

    public List<String> findBusinessNumbers(UUID tenantId, String scope) {
        return jdbcTemplate.queryForList("""
                SELECT business_number FROM tenancy_business_number
                 WHERE tenant_id = ? AND scope = ?
                 ORDER BY sequence
                """, String.class, tenantId, scope);
    }

    public void insertAudit(TenantLifecycleAudit audit) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_lifecycle_audit
                    (id, tenant_id, action, from_status, to_status, actor, reason, ticket_id,
                     correlation_id, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, audit.id(), audit.tenantId(), audit.action(),
                audit.fromStatus() == null ? null : audit.fromStatus().wire(),
                audit.toStatus().wire(), audit.actor(), audit.reason(), audit.ticketId(),
                audit.correlationId(), audit.traceId());
    }

    public List<TenantLifecycleAudit> findAudit(UUID tenantId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, action, from_status, to_status, actor, reason, ticket_id,
                       correlation_id, trace_id, occurred_at
                  FROM tenancy_lifecycle_audit
                 WHERE tenant_id = ?
                 ORDER BY occurred_at DESC, id
                 LIMIT ?
                """, AUDIT_MAPPER, tenantId, limit);
    }

    public void insertAppeal(UUID id, UUID tenantId, String subjectId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO tenancy_appeal (id, tenant_id, subject_id, reason)
                VALUES (?, ?, ?, ?)
                """, id, tenantId, subjectId, reason);
    }

    public List<TenantAppeal> findAppeals(UUID tenantId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, subject_id, reason, status, submitted_at
                  FROM tenancy_appeal
                 WHERE tenant_id = ?
                 ORDER BY submitted_at DESC
                 LIMIT ?
                """, APPEAL_MAPPER, tenantId, limit);
    }

    private boolean updateStatus(UUID tenantId, String from, String to) {
        int updated = jdbcTemplate.update("""
                UPDATE tenancy_tenant
                   SET status = ?, updated_at = now(), version = version + 1
                 WHERE id = ? AND status = ?
                """, to, tenantId, from);
        return updated == 1;
    }

    private String json(Map<String, Boolean> features) {
        return objectMapper.writeValueAsString(features);
    }
}
