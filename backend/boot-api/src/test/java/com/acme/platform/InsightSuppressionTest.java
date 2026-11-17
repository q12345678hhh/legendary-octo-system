package com.acme.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.platform.department.DepartmentSpaceStore;
import com.acme.platform.department.insight.InsightService;
import com.acme.platform.department.insight.InsightStore;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.tenancy.SupportAccessStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * WP03-04 洞察抑制证据：小群体、筛选后小群体、差分查询与草稿隔离。
 *
 * <p>真实 PostgreSQL + 真实 {@link InsightStore}；部门成员由 mock 提供，
 * 以便精确控制「成员数」与「贡献人数」两个口径。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class InsightSuppressionTest {

    private static final LocalDate WEEK_ONE = LocalDate.of(2026, 11, 2);
    private static final LocalDate WEEK_TWO = LocalDate.of(2026, 11, 9);

    private JdbcTemplate jdbc;
    private TenantTransactions transactions;
    private InsightStore store;
    private InsightService service;
    private DepartmentSpaceStore spaceStore;
    private UUID tenantId;
    private UUID unitId;
    private final List<UUID> people = new ArrayList<>();

    @Test
    void suppressionSuiteKeepsSmallGroupsAndDraftsOut() {
        setUp();
        UUID[] ids = people.toArray(UUID[]::new);

        // 1. 成员 4 人：低于阈值 5，即使全部提交也整格抑制
        setMembers(ids, 4);
        submit(ids, WEEK_ONE, 4);
        InsightService.Overview smallGroup = service.overview(unitId, WEEK_ONE, WEEK_ONE.plusDays(4));
        assertThat(smallGroup.suppressed()).isTrue();
        assertThat(smallGroup.memberCount()).isNull();
        assertThat(smallGroup.submittedPersonDays()).isNull();
        assertThat(smallGroup.taskStatus()).isEmpty();
        assertThat(smallGroup.weeks()).isEmpty();

        // 2. 成员 6 人但只有 3 人提交：贡献人数 3 < 5，差分查询也无法反推
        resetDays();
        setMembers(ids, 6);
        submit(ids, WEEK_ONE, 3);
        InsightService.Overview fewContributors =
                service.overview(unitId, WEEK_ONE, WEEK_ONE.plusDays(4));
        assertThat(fewContributors.suppressed()).isTrue();
        assertThat(fewContributors.submittedPersonDays()).isNull();

        // 3. 5 人提交 + 1 人只有草稿：草稿不进入统计，提交人日必须恰好为 5
        resetDays();
        submit(ids, WEEK_ONE, 5);
        draft(ids[5], WEEK_ONE);
        InsightService.Overview healthy = service.overview(unitId, WEEK_ONE, WEEK_ONE.plusDays(4));
        assertThat(healthy.suppressed()).isFalse();
        assertThat(healthy.submittedPersonDays()).isEqualTo(5);
        assertThat(healthy.totalSubmittedMinutes()).isEqualTo(5L * 120L);
        assertThat(healthy.memberCount()).isEqualTo(6);

        // 4. 周维度：本周 3 人提交 < 5，整行抑制；不返回该周的人日数
        submit(ids, WEEK_TWO, 3);
        InsightService.Overview weekly =
                service.overview(unitId, WEEK_ONE, WEEK_TWO.plusDays(4));
        assertThat(weekly.suppressed()).isFalse();
        assertThat(weekly.weeks()).hasSize(2);
        assertThat(weekly.weeks().get(0).suppressed()).isFalse();
        assertThat(weekly.weeks().get(0).submittedPersonDays()).isEqualTo(5);
        assertThat(weekly.weeks().get(1).suppressed()).isTrue();
        assertThat(weekly.weeks().get(1).submittedPersonDays()).isNull();

        // 5. 下钻：满足阈值时返回成员投影；低于阈值时拒绝
        List<InsightStore.PersonRow> rows = service.drilldown(unitId, WEEK_ONE, WEEK_ONE.plusDays(4));
        assertThat(rows).hasSize(6);
        setMembers(ids, 4);
        assertThatThrownBy(() -> service.drilldown(unitId, WEEK_ONE, WEEK_ONE.plusDays(4)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INSIGHT_GROUP_TOO_SMALL"));

        // 6. 阈值只能提高：5 → 8 允许，8 → 7 拒绝
        setMembers(ids, 6);
        InsightStore.Setting raised = service.updateMinGroupSize(8, "tester");
        assertThat(raised.minGroupSize()).isEqualTo(8);
        assertThatThrownBy(() -> service.updateMinGroupSize(7, "tester"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INSIGHT_THRESHOLD_DECREASE"));
        restoreThreshold();
    }

    private void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TenantTransactions(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

        String suffix = Long.toString(System.nanoTime(), 36);
        tenantId = UUID.randomUUID();
        unitId = UUID.randomUUID();
        TenantLifecycleService lifecycle = new TenantLifecycleService(transactions,
                new TenantStore(jdbc, new ObjectMapper()), new SupportAccessStore(jdbc),
                Clock.systemUTC(), 30);
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantId,
                "insight-" + suffix, "洞察测试", "standard", null, null, "zh-CN",
                "Asia/Shanghai", "insight-" + suffix + ".example.test", List.of(), null), "ops");

        transactions.inTenant(new RequestContext(tenantId, "insight-test", "trace"), context -> {
            for (int index = 0; index < 6; index += 1) {
                UUID personId = UUID.randomUUID();
                people.add(personId);
                jdbc.update("""
                        INSERT INTO organization_person (id, tenant_id, display_name, status)
                        VALUES (?, ?, ?, 'active')
                        """, personId, tenantId, "员工" + index);
            }
            return null;
        });

        spaceStore = mock(DepartmentSpaceStore.class);
        store = new InsightStore(jdbc);
        service = new InsightService(store, spaceStore, transactions);
        // 与真实请求一致：单参 inTenant 从线程上下文取租户
        RequestContextHolder.set(new RequestContext(tenantId, "insight-test", "trace"));
    }

    private void setMembers(UUID[] ids, int count) {
        List<DepartmentSpaceStore.MemberRow> members = new ArrayList<>();
        for (int index = 0; index < count; index += 1) {
            members.add(new DepartmentSpaceStore.MemberRow(UUID.randomUUID(), ids[index],
                    "员工" + index, "工程师", "工程师", "primary", null, 1));
        }
        when(spaceStore.members(eq(unitId), any())).thenReturn(members);
    }

    private void submit(UUID[] ids, LocalDate workDate, int count) {
        for (int index = 0; index < count; index += 1) {
            insertDay(ids[index], workDate, "submitted", 120, "submitted-" + workDate + index);
        }
    }

    private void draft(UUID personId, LocalDate workDate) {
        insertDay(personId, workDate, "draft", 600, "draft-" + workDate);
    }

    private void insertDay(UUID personId, LocalDate workDate, String state, int minutes,
                           String clientEntryId) {
        transactions.inTenant(new RequestContext(tenantId, "insight-test", "trace"), context -> {
            jdbc.update("""
                    INSERT INTO worklog_day (tenant_id, person_id, work_date, state)
                    VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                    ON CONFLICT (tenant_id, person_id, work_date) DO UPDATE SET state = EXCLUDED.state
                    """, personId, workDate, state);
            jdbc.update("""
                    INSERT INTO worklog_entry
                        (tenant_id, person_id, work_date, duration_minutes, description, client_entry_id)
                    VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, '', ?)
                    """, personId, workDate, minutes, clientEntryId);
            return null;
        });
    }

    private void resetDays() {
        transactions.inTenant(new RequestContext(tenantId, "insight-test", "trace"), context -> {
            jdbc.update("DELETE FROM worklog_entry");
            jdbc.update("DELETE FROM worklog_day");
            return null;
        });
    }

    private void restoreThreshold() {
        transactions.inTenant(new RequestContext(tenantId, "insight-test", "trace"), context -> {
            jdbc.update("""
                    UPDATE worklog_setting SET min_group_size = 5
                     WHERE tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
                    """);
            return null;
        });
        RequestContextHolder.clear();
    }
}
