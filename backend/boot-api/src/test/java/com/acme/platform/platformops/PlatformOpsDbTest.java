package com.acme.platform.platformops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.audit.PlatformAuditStore;
import com.acme.platform.platform.audit.PlatformOperatorRef;
import com.acme.platform.platform.directory.PlatformDirectoryStore;
import com.acme.platform.platform.overview.PlatformOverviewService;
import com.acme.platform.platform.security.PlatformSecurityService;
import com.acme.platform.platform.security.PlatformSecurityStore;
import com.acme.platform.platform.settings.PlatformSettingService;
import com.acme.platform.platform.settings.PlatformSettingStore;
import com.acme.platform.tenancy.SupportAccessStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantStore;
import com.acme.platform.tenancy.TenantSupportAccessService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的平台运营端闭环。
 *
 * <p>覆盖：目录投影同步、平台设置白名单与密钥脱敏、租户数上限、支持访问时限上限、
 * 安全事件状态机、审计不可改删与总览错误率。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class PlatformOpsDbTest {

    @Test
    void platformOperationsVerification() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        PlatformDirectoryStore directory = new PlatformDirectoryStore(jdbc);
        PlatformAuditStore audit = new PlatformAuditStore(jdbc, mapper);
        PlatformSettingStore settingStore = new PlatformSettingStore(jdbc);
        PlatformSettingService settings = new PlatformSettingService(settingStore, audit, mapper);
        PlatformSecurityStore securityStore = new PlatformSecurityStore(jdbc);
        PlatformSecurityService security = new PlatformSecurityService(securityStore, audit, mapper);

        TenantStore tenantStore = new TenantStore(jdbc, mapper);
        TenantLifecycleService lifecycle = new TenantLifecycleService(
                new com.acme.platform.platform.TenantTransactions(jdbc, transactionTemplate),
                tenantStore, new SupportAccessStore(jdbc), clock, 30);
        TenantSupportAccessService supportAccess = new TenantSupportAccessService(
                new com.acme.platform.platform.TenantTransactions(jdbc, transactionTemplate),
                tenantStore, new SupportAccessStore(jdbc), clock);

        String suffix = Long.toString(System.nanoTime(), 36);

        UUID operatorId = UUID.randomUUID();
        String operatorEmail = "ops-" + suffix + "@example.test";
        jdbc.update("""
                INSERT INTO platform_operator (id, email, display_name, role_code, password_hash)
                VALUES (?, ?, '平台运营验证', 'platform-owner', 'hash')
                """, operatorId, operatorEmail);
        PlatformOperatorRef operator = new PlatformOperatorRef(operatorId, operatorEmail);

        // 1. 目录投影：创建租户后平台目录立即可见，且不含业务正文
        UUID tenant = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenant,
                "ops-" + suffix, "平台运营验证租户", "standard", null, null, "zh-CN",
                "Asia/Shanghai", "ops-" + suffix + ".example.test", List.of(), null), "ops");
        PlatformDirectoryStore.TenantSummary summary = directory.find(tenant).orElseThrow();
        assertThat(summary.status()).isEqualTo("provisioning");
        assertThat(summary.name()).isEqualTo("平台运营验证租户");

        // 2. 设置：白名单、范围校验、密钥只写不读、变更留审计
        long auditBefore = jdbc.queryForObject(
                "SELECT count(*) FROM platform_audit_event WHERE action = 'settings.update'",
                Long.class);
        String secretValue = "s3cr3t-" + suffix;
        PlatformSettingService.SettingView secret = settings.put("email.password",
                mapper.readTree(mapper.writeValueAsString(secretValue)), operator);
        assertThat(secret.secret()).isTrue();
        assertThat(secret.configured()).isTrue();
        assertThat(secret.value()).isNull();
        String stored = jdbc.queryForObject(
                "SELECT value #>> '{}' FROM platform_setting WHERE setting_key = 'email.password'",
                String.class);
        assertThat(stored).isEqualTo(secretValue);

        assertThatThrownBy(() -> settings.put("ops.unknown", mapper.readTree("\"x\""), operator))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SETTING_UNKNOWN_KEY"));
        assertThatThrownBy(() -> settings.put("limits.max_tenants",
                mapper.readTree("0"), operator))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SETTING_VALUE_OUT_OF_RANGE"));
        long auditAfter = jdbc.queryForObject(
                "SELECT count(*) FROM platform_audit_event WHERE action = 'settings.update'",
                Long.class);
        assertThat(auditAfter).isEqualTo(auditBefore + 1);

        // 3. 平台审计不可改删
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_audit_event (id, action, target_type, target_id)
                VALUES (?, 'verify.probe', 'platform_audit_event', ?)
                """, auditId, auditId.toString());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE platform_audit_event SET action = 'tampered' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM platform_audit_event WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class);

        // 4. 租户数上限真实生效
        int originalMaxTenants = settingStore.intValue("limits.max_tenants").orElse(20);
        long currentTenants = directory.count();
        try {
            settings.put("limits.max_tenants", mapper.readTree(Long.toString(currentTenants)),
                    operator);
            assertThatThrownBy(() -> lifecycle.create(new TenantLifecycleService.CreateTenantCommand(
                    UUID.randomUUID(), "ops-limit-" + suffix, "超限租户", "standard", null, null,
                    "zh-CN", "Asia/Shanghai", "ops-limit-" + suffix + ".example.test",
                    List.of(), null), "ops"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("TENANT_LIMIT_REACHED"));
        } finally {
            settings.put("limits.max_tenants",
                    mapper.readTree(Integer.toString(originalMaxTenants)), operator);
        }

        // 5. 支持访问时限上限真实生效
        int originalSupportMinutes = settingStore.intValue("limits.support_access_max_minutes")
                .orElse(60);
        try {
            settings.put("limits.support_access_max_minutes", mapper.readTree("5"), operator);
            assertThatThrownBy(() -> supportAccess.request(tenant, "ops-a", "TCK-9001",
                    "核对跨租户导出链路的审计记录", 10))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("SUPPORT_DURATION_INVALID"));
        } finally {
            settings.put("limits.support_access_max_minutes",
                    mapper.readTree(Integer.toString(originalSupportMinutes)), operator);
        }

        // 6. 安全事件状态机与类别校验
        UUID eventId = security.record(tenant, "incident", "high", "验证用安全事件",
                Map.of("source", "db-test"), operator);
        assertThat(security.acknowledge(eventId, operator).status()).isEqualTo("acknowledged");
        assertThat(security.resolve(eventId, operator).status()).isEqualTo("resolved");
        assertThatThrownBy(() -> security.record(null, "unknown", "high", "未知类别事件",
                Map.of(), operator))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SECURITY_CATEGORY_INVALID"));

        // 7. 指标幂等与总览错误率
        directory.upsertMetric(tenant, LocalDate.now(clock), 3, 0, 0, 1024, 0, 0, 1, 0, 0);
        directory.upsertMetric(tenant, LocalDate.now(clock), 5, 0, 0, 2048, 0, 0, 2, 0, 0);
        Long metricRows = jdbc.queryForObject("""
                SELECT count(*) FROM platform_tenant_metric_daily
                 WHERE tenant_id = ? AND day = ?
                """, Long.class, tenant, LocalDate.now(clock));
        assertThat(metricRows).isEqualTo(1L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("platform.http.requests").tag("status", "200").register(registry)
                .increment(3);
        Counter.builder("platform.http.requests").tag("status", "500").register(registry)
                .increment(1);
        PlatformOverviewService overview = new PlatformOverviewService(directory, jdbc, registry,
                clock);
        PlatformOverviewService.Overview view = overview.overview();
        assertThat(view.httpErrorRate()).isEqualTo(0.25);
        assertThat(view.tenantTotal()).isGreaterThanOrEqualTo(1);
        assertThat(view.tenantsByStatus()).containsKey("provisioning");
        assertThat(view.stale()).isFalse();
    }
}
