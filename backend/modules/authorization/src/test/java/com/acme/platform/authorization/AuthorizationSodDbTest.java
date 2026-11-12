package com.acme.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.platform.identity.MemberStore;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.tenancy.SupportAccessStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的职责分离与季度访问复核闭环。
 *
 * <p>覆盖：申请→他人批准→授权生效、高权限无申请被拒、静态角色冲突、
 * 季度生成幂等、逾期升级、宽限期后自动暂停与审计不可改删。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class AuthorizationSodDbTest {

    private TenantTransactions tenantTransactions;
    private JdbcTemplate jdbc;

    @Test
    void separationAndQuarterlyReviewLifecycle() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tenantTransactions = new TenantTransactions(jdbc, transactionTemplate);
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        TenantStore tenantStore = new TenantStore(jdbc, mapper);
        TenantLifecycleService lifecycle = new TenantLifecycleService(tenantTransactions,
                tenantStore, new SupportAccessStore(jdbc), clock, 30);
        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenant = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenant,
                "sod-" + suffix, "职责分离租户", "standard", null, null, "zh-CN",
                "Asia/Shanghai", "sod-" + suffix + ".example.test", List.of(), null), "ops");

        AuthorizationProperties properties = new AuthorizationProperties();
        AuthorizationStore store = new AuthorizationStore(jdbc);
        CacheVersionStore versions = new CacheVersionStore(jdbc, properties);
        AuthorizationCache cache = new AuthorizationCache(properties);
        OrganizationScopeQueries organizationScopes = new OrganizationScopeQueries(jdbc);
        MemberStore members = new MemberStore(jdbc);
        AuthorizationService authorization = new AuthorizationService(tenantTransactions, store,
                versions, cache, new DerivedGrantResolver(), new ScopeResolver(organizationScopes),
                new ConditionEvaluator(mapper), new FieldPolicyEvaluator(store), members,
                organizationScopes, new SodChecker(), (subject, request) -> false, clock, mapper);
        AuthorizationAdminService admin = new AuthorizationAdminService(tenantTransactions,
                store, cache, versions, new ScopeResolver(organizationScopes),
                new ConditionEvaluator(mapper), organizationScopes, members,
                new AccessRequestStore(jdbc), new AdminActionStore(jdbc), properties, mapper, clock);
        AuthorizationGuard guard = new AuthorizationGuard(authorization);
        AccessRequestService requests = new AccessRequestService(tenantTransactions,
                new AccessRequestStore(jdbc), store, admin, guard, authorization,
                new AdminActionStore(jdbc), mapper, clock);
        AccessReviewService reviews = new AccessReviewService(tenantTransactions,
                new AccessReviewStore(jdbc), store, organizationScopes, guard, authorization,
                versions, new AdminActionStore(jdbc), properties, mapper, clock);

        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID conflictSubject = UUID.randomUUID();
        asTenant(tenant, () -> {
            jdbc.update("""
                    INSERT INTO identity_actor (id, tenant_id, actor_type, display_name)
                    VALUES (?, ?, 'service', '申请人'), (?, ?, 'service', '审批人'),
                           (?, ?, 'service', '被授权人'), (?, ?, 'service', '冲突主体')
                    """, requester, tenant, approver, tenant, subject, tenant,
                    conflictSubject, tenant);
            jdbc.update("""
                    INSERT INTO authorization_assignment
                        (tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by)
                    VALUES (?, ?, 'security-admin', 'tenant', now() - interval '1 day',
                            '测试审批人', 'verify')
                    """, tenant, approver);
            jdbc.update("""
                    INSERT INTO authorization_assignment
                        (tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by)
                    VALUES (?, ?, 'payroll-preparer', 'tenant', now() - interval '1 day',
                            '测试冲突角色', 'verify')
                    """, tenant, conflictSubject);
            return null;
        });

        // 1. 高权限无申请：非所有者被拒
        assertThatThrownBy(() -> as(tenant, approver, () -> admin.grant(
                new AuthorizationAdminService.GrantCommand(subject, null, "payroll-approver",
                        "tenant", null, null, OffsetDateTime.now(clock).plusDays(10), "直接授予",
                        null, null))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("ACCESS_REQUEST_REQUIRED");
                });

        // 2. 申请后由他人批准，授权携带 source_request_id
        UUID requestId = as(tenant, requester, () -> requests.create(
                new AccessRequestService.CreateCommand(subject, "payroll-approver", "tenant",
                        null, OffsetDateTime.now(clock).plusDays(10), "薪酬批准需要")).id());
        AccessRequestStore.AccessRequestRow approved = as(tenant, approver,
                () -> requests.approve(requestId, "同意"));
        assertThat(approved.state()).isEqualTo("approved");
        assertThat(approved.assignmentId()).isNotNull();
        UUID payrollAssignment = approved.assignmentId();
        assertThat(asTenant(tenant, () -> jdbc.queryForObject("""
                SELECT source_request_id FROM authorization_assignment WHERE id = ?
                """, UUID.class, payrollAssignment))).isEqualTo(requestId);

        // 3. 申请人自己批准被拒
        UUID selfRequest = as(tenant, requester, () -> requests.create(
                new AccessRequestService.CreateCommand(subject, "payroll-reviewer", "tenant",
                        null, OffsetDateTime.now(clock).plusDays(10), "复核需要")).id());
        assertThatThrownBy(() -> as(tenant, requester,
                () -> requests.approve(selfRequest, "自己批")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SOD_SELF_APPROVAL"));

        // 4. 静态角色冲突：已有 payroll-preparer 再授予 payroll-approver
        UUID conflictRequest = as(tenant, requester, () -> requests.create(
                new AccessRequestService.CreateCommand(conflictSubject, "payroll-approver",
                        "tenant", null, OffsetDateTime.now(clock).plusDays(10), "冲突申请")).id());
        assertThatThrownBy(() -> as(tenant, approver,
                () -> requests.approve(conflictRequest, "同意")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SOD_ROLE_CONFLICT"));
        assertThat(asTenant(tenant, () -> jdbc.queryForObject(
                "SELECT state FROM authorization_access_request WHERE id = ?", String.class,
                conflictRequest))).isEqualTo("pending");

        // 5. 季度复核：同一季度幂等
        AccessReviewService.ReviewDetail detail = as(tenant, approver,
                () -> reviews.generateCurrentTenant());
        assertThat(detail.items()).isNotEmpty();
        assertThat(detail.review().state()).isIn("open", "overdue", "closed");
        int itemCount = detail.items().size();
        AccessReviewService.ReviewDetail again = as(tenant, approver,
                () -> reviews.generateCurrentTenant());
        assertThat(again.review().id()).isEqualTo(detail.review().id());
        assertThat(again.items()).hasSize(itemCount);

        // 6. 管理视角确认一条，剩余条目逾期后自动暂停
        long versionBefore = asTenant(tenant, versions::currentVersion);
        AccessReviewStore.ItemRow pending = detail.items().stream()
                .filter(item -> "pending".equals(item.state()))
                .filter(item -> !item.subjectActorId().equals(approver))
                .findFirst().orElseThrow();
        as(tenant, approver, () -> reviews.confirm(detail.review().id(), pending.id(), "确认保留"));

        asTenant(tenant, () -> {
            jdbc.update("""
                    UPDATE authorization_access_review
                       SET due_at = now() - interval '8 days', updated_at = now()
                     WHERE id = ?
                    """, detail.review().id());
            jdbc.update("""
                    UPDATE platform_access_review_due
                       SET updated_at = now() - interval '2 hours'
                     WHERE tenant_id = ?
                    """, tenant);
            return null;
        });
        int revoked = reviews.processOverdue(50);
        assertThat(revoked).isGreaterThanOrEqualTo(1);
        assertThat(asTenant(tenant, () -> jdbc.queryForObject("""
                SELECT state FROM authorization_access_review WHERE id = ?
                """, String.class, detail.review().id()))).isEqualTo("closed");
        assertThat(asTenant(tenant, () -> jdbc.queryForObject("""
                SELECT count(*) FROM authorization_access_review_item
                 WHERE review_id = ? AND state = 'pending'
                """, Long.class, detail.review().id()))).isZero();
        assertThat(asTenant(tenant, versions::currentVersion)).isGreaterThan(versionBefore);

        // 7. 管理员审计只读
        assertThat(as(tenant, approver, () -> admin.listAdminActions(50)))
                .extracting(AdminActionStore.AdminActionRow::action)
                .contains("ACCESS_REQUEST_APPROVED", "ACCESS_REVIEW_AUTO_REVOKED");
    }

    private <T> T as(UUID tenant, UUID actor, Supplier<T> action) {
        RequestContextHolder.set(new RequestContext(tenant, actor.toString(),
                UUID.randomUUID().toString(), "t"));
        ActorContextHolder.set(new ActorContext(tenant, actor, null, null, "service", true, null));
        try {
            return action.get();
        } finally {
            RequestContextHolder.clear();
            ActorContextHolder.clear();
        }
    }

    private <T> T asTenant(UUID tenant, Supplier<T> action) {
        RequestContextHolder.set(new RequestContext(tenant, UUID.randomUUID().toString(), "t"));
        try {
            return tenantTransactions.inTenant(context -> action.get());
        } finally {
            RequestContextHolder.clear();
        }
    }
}
