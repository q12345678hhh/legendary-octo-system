package com.acme.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的租户生命周期闭环。
 *
 * <p>只有设置 {@code -Dplatform.db.url} 才执行；CI 的 backend 作业不启动数据库，
 * 由 db 作业在迁移完成后用 platform_app 身份执行。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class TenantLifecycleDbTest {

    @Test
    void lifecycleStateMachineBlocksWritesAndKeepsAudit() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantTransactions tenantTransactions = new TenantTransactions(jdbcTemplate, transactionTemplate);
        TenantStore store = new TenantStore(jdbcTemplate, new ObjectMapper());
        SupportAccessStore supportStore = new SupportAccessStore(jdbcTemplate);
        TenantLifecycleService lifecycle = new TenantLifecycleService(
                tenantTransactions, store, supportStore, Clock.systemUTC(), 30);
        TenantSupportAccessService support = new TenantSupportAccessService(
                tenantTransactions, store, supportStore, Clock.systemUTC());
        TenantExecutionGate gate = new TenantExecutionGate(new TenantStatusQueries(tenantTransactions, store));

        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        RequestContext contextA = context(tenantA, "ops-a");
        RequestContext contextB = context(tenantB, "ops-b");

        TenantRecord a = lifecycle.create(new TenantLifecycleService.CreateTenantCommand(
                tenantA, "lc-a-" + suffix, "生命周期甲", "standard", null, null,
                "zh-CN", "Asia/Shanghai", "a-" + suffix + ".example.test", List.of(), null), "ops-a");
        TenantRecord b = lifecycle.create(new TenantLifecycleService.CreateTenantCommand(
                tenantB, "lc-b-" + suffix, "生命周期乙", "standard", null, null,
                "zh-CN", "Asia/Shanghai", "b-" + suffix + ".example.test", List.of(), null), "ops-b");
        assertThat(a.status()).isEqualTo(TenantStatus.PROVISIONING);
        assertThat(b.status()).isEqualTo(TenantStatus.PROVISIONING);

        TenantProfile profile = tenantTransactions.inTenant(contextA,
                ctx -> store.findProfile(tenantA).orElseThrow());
        assertThat(profile.locale()).isEqualTo("zh-CN");
        assertThat(profile.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(profile.features()).containsEntry("plugins", true).containsEntry("ai", false);
        List<TenantQuota> quotasA = tenantTransactions.inTenant(contextA, ctx -> store.findQuotas(tenantA));
        assertThat(quotasA)
                .hasSize(4)
                .anySatisfy(quota -> {
                    assertThat(quota.key()).isEqualTo("users");
                    assertThat(quota.limit()).isEqualTo(50);
                });

        lifecycle.initialize(tenantA, "ops-a");
        lifecycle.initialize(tenantB, "ops-b");

        TenantLifecycleService.BusinessNumber numberA =
                lifecycle.issueBusinessNumber(tenantA, "WO", "ops-a");
        TenantLifecycleService.BusinessNumber numberB =
                lifecycle.issueBusinessNumber(tenantB, "WO", "ops-b");
        assertThat(numberA.number()).isEqualTo("WO-000001");
        assertThat(numberB.number()).isEqualTo("WO-000001");
        List<String> numbersA = tenantTransactions.inTenant(contextA,
                ctx -> store.findBusinessNumbers(tenantA, "WO"));
        List<String> numbersB = tenantTransactions.inTenant(contextB,
                ctx -> store.findBusinessNumbers(tenantB, "WO"));
        assertThat(numbersA).containsExactly("WO-000001");
        assertThat(numbersB).containsExactly("WO-000001");

        lifecycle.freeze(tenantA, "安全事件复核期间冻结", "ops-a");
        assertThatThrownBy(() -> gate.assertBusinessWrite(contextA))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(423);
                    assertThat(error.code()).isEqualTo("TENANT_FROZEN");
                });
        assertThatThrownBy(() -> gate.assertPluginExecution(contextA))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("TENANT_PLUGIN_BLOCKED"));
        assertThatThrownBy(() -> gate.assertAiExecution(contextA))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("TENANT_AI_BLOCKED"));
        assertThatThrownBy(() -> lifecycle.consumeQuota(tenantA, "users", 1, "ops-a"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.status()).isEqualTo(423));

        TenantSupportAccess access = support.request(tenantA, "ops-a", "TCK-1001",
                "核对冻结后导出结果与审计链", 5);
        assertThat(access.status()).isEqualTo(SupportAccessStatus.PENDING);
        TenantSupportAccess approved = support.approve(tenantA, access.id(), "ops-b");
        assertThat(approved.status()).isEqualTo(SupportAccessStatus.APPROVED);
        assertThat(approved.expiresAt()).isAfter(approved.approvedAt());

        TenantSupportAccessService.SupportGrant grant = support.enter(tenantA, access.id());
        assertThat(grant.readOnly()).isTrue();
        assertThat(grant.banner().ticketId()).isEqualTo("TCK-1001");
        assertThat(grant.banner().message()).contains("只读");

        tenantTransactions.inTenant(contextA, ctx -> {
            jdbcTemplate.update("""
                    UPDATE tenancy_support_access SET expires_at = now() - interval '1 minute'
                     WHERE id = ?
                    """, access.id());
            return null;
        });
        assertThatThrownBy(() -> support.enter(tenantA, access.id()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(403);
                    assertThat(error.code()).isEqualTo("SUPPORT_ACCESS_EXPIRED");
                });

        lifecycle.restore(tenantA, "复核完成，恢复业务", "ops-a");
        assertThat(gate.assertBusinessWrite(contextA).status()).isEqualTo(TenantStatus.ACTIVE);

        TenantLifecycleService.TenantExport export = lifecycle.export(tenantA, "ops-a");
        assertThat(export.notice()).contains("受控导出");
        assertThat(export.tenant().status()).isEqualTo(TenantStatus.ACTIVE);

        lifecycle.requestClosing(tenantA, "业务调整，申请注销", "ops-a");
        assertThatThrownBy(() -> gate.assertAiExecution(contextA))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("TENANT_AI_BLOCKED"));

        TenantAppeal appeal = lifecycle.submitAppeal(tenantA, "employee-1", "冷静期内需要导出个人工时记录");
        assertThat(appeal.status()).isEqualTo("submitted");

        assertThatThrownBy(() -> lifecycle.delete(tenantA, "冷静期结束前清理", "ops-a"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("COOLING_OFF_ACTIVE");
                });

        tenantTransactions.inTenant(contextA, ctx -> {
            jdbcTemplate.update("""
                    UPDATE tenancy_tenant SET cooling_off_until = now() - interval '1 minute'
                     WHERE id = ?
                    """, tenantA);
            return null;
        });
        TenantRecord deleted = lifecycle.delete(tenantA, "冷静期结束，最终删除", "ops-a");
        assertThat(deleted.status()).isEqualTo(TenantStatus.DELETED);
        assertThat(deleted.deletedAt()).isNotNull();
        List<TenantDomain> remainingDomains = tenantTransactions.inTenant(contextA,
                ctx -> store.findDomains(tenantA));
        assertThat(remainingDomains).isEmpty();
        assertThatThrownBy(() -> gate.assertBusinessWrite(contextA))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(410);
                    assertThat(error.code()).isEqualTo("TENANT_DELETED");
                });

        List<String> actions = tenantTransactions.inTenant(contextA,
                ctx -> store.findAudit(tenantA, 100).stream().map(TenantLifecycleAudit::action).toList());
        assertThat(actions).contains("created", "initialized", "frozen", "restored", "exported",
                "closing_requested", "appeal_submitted", "deleted");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenancy_tenant", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenancy_business_number", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM work_assistant_segment", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenancy_plan", Long.class))
                .isGreaterThanOrEqualTo(3);
    }

    private RequestContext context(UUID tenantId, String actor) {
        return new RequestContext(tenantId, actor, UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""));
    }
}
