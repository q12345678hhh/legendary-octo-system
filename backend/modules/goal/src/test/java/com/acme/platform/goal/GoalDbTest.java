package com.acme.platform.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.tenancy.SupportAccessStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 团队目标与关键结果在真实 PostgreSQL 上的隔离验证。
 *
 * <p>覆盖：同租户可读写、另一租户看不见也改不动、跨租户直插被 WITH CHECK 拒绝。
 * RLS 由运行期账号 platform_app（NOBYPASSRLS）真实生效。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class GoalDbTest {

    @Test
    void teamGoalAndKeyResultAreTenantIsolated() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TenantTransactions transactions = new TenantTransactions(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        Clock clock = Clock.systemUTC();
        TenantLifecycleService lifecycle = new TenantLifecycleService(transactions,
                new TenantStore(jdbc, new ObjectMapper()), new SupportAccessStore(jdbc), clock, 30);
        GoalStore store = new GoalStore(jdbc);

        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantA,
                "goal-a-" + suffix, "目标甲", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "goal-a-" + suffix + ".example.test", List.of(), null), "ops");
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantB,
                "goal-b-" + suffix, "目标乙", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "goal-b-" + suffix + ".example.test", List.of(), null), "ops");

        UUID unitId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID goalId = as(transactions, tenantA, () -> store.insertTeam(ownerId, unitId,
                "部门目标", "", "2026-Q4", null, "actor"));
        UUID keyResultId = as(transactions, tenantA, () -> store.insertKeyResult(goalId,
                "签约客户", BigDecimal.TEN, BigDecimal.ONE, "家", 0));

        // 本租户：目标与 KR 均可读可写
        assertThat(as(transactions, tenantA, () -> store.listByUnit(unitId))).hasSize(1);
        assertThat(as(transactions, tenantA, () -> store.findKeyResult(keyResultId))).isPresent();
        assertThat(as(transactions, tenantA,
                () -> store.updateKeyResultCurrent(keyResultId, BigDecimal.valueOf(2), 1L)))
                .isTrue();

        // 另一租户：不可见、不可改、不可删
        assertThat(as(transactions, tenantB, () -> store.listByUnit(unitId))).isEmpty();
        assertThat(as(transactions, tenantB, () -> store.findKeyResult(keyResultId))).isEmpty();
        assertThat(as(transactions, tenantB,
                () -> store.updateKeyResultCurrent(keyResultId, BigDecimal.TEN, 2L))).isFalse();
        assertThat(as(transactions, tenantB, () -> store.deleteKeyResult(keyResultId))).isFalse();

        // 用 B 的上下文直插 A 的租户数据：WITH CHECK 拒绝
        assertThatThrownBy(() -> as(transactions, tenantB, () -> jdbc.update("""
                INSERT INTO work_goal_key_result
                    (tenant_id, goal_id, title, target_value, current_value)
                VALUES (?, ?, ?, ?, 0)
                """, tenantA, goalId, "越权写入", BigDecimal.ONE)))
                .isInstanceOf(DataAccessException.class);
    }

    private static <T> T as(TenantTransactions transactions, UUID tenantId, Supplier<T> work) {
        return transactions.inTenant(new RequestContext(tenantId, "actor", "corr", "trace"),
                context -> work.get());
    }
}
