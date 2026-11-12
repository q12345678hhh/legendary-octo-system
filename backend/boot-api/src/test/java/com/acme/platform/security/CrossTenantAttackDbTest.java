package com.acme.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.platform.authorization.AccessRequestService;
import com.acme.platform.authorization.AccessRequestStore;
import com.acme.platform.authorization.AccessReviewService;
import com.acme.platform.authorization.AccessReviewStore;
import com.acme.platform.authorization.AdminActionStore;
import com.acme.platform.authorization.AuthorizationAdminService;
import com.acme.platform.authorization.AuthorizationCache;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.AuthorizationProperties;
import com.acme.platform.authorization.AuthorizationService;
import com.acme.platform.authorization.AuthorizationStore;
import com.acme.platform.authorization.CacheVersionStore;
import com.acme.platform.authorization.ConditionEvaluator;
import com.acme.platform.authorization.DerivedGrantResolver;
import com.acme.platform.authorization.FieldPolicyEvaluator;
import com.acme.platform.authorization.ScopeResolver;
import com.acme.platform.authorization.SodChecker;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.organization.DirectoryScope;
import com.acme.platform.organization.EmploymentStore;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.organization.PeopleService;
import com.acme.platform.organization.PersonStore;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.PlatformMetrics;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import com.acme.platform.platform.TenantTransactions;
import com.acme.platform.platform.file.FileService;
import com.acme.platform.platform.file.FileStore;
import com.acme.platform.platform.job.JobService;
import com.acme.platform.platform.job.JobStore;
import com.acme.platform.platform.storage.ObjectStorageClient;
import com.acme.platform.platform.storage.ObjectStorageProperties;
import com.acme.platform.platform.storage.S3Presigner;
import com.acme.platform.tenancy.SupportAccessStore;
import com.acme.platform.tenancy.TenantLifecycleService;
import com.acme.platform.tenancy.TenantStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * WP02-06 服务层跨租户攻击套件。
 *
 * <p>两个「看起来一样」的租户：同名、同姓名、同邮箱、同工号、同文件名，只有 UUID 不同。
 * 所有用例从 A 的上下文用 B 的已知标识发起，期望全部被拒；任一例失败即整体失败。
 *
 * <p>真实 PostgreSQL + 真实服务 + 真实 RLS；不引入完整 Spring 上下文。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class CrossTenantAttackDbTest {

    private static final String FIXTURE_EVENT = "platform.fixture.v1";

    private JdbcTemplate jdbc;
    private TenantTransactions tenantTransactions;
    private Clock clock;

    private PeopleService people;
    private FileService files;
    private JobService jobs;
    private AuthorizationAdminService admin;
    private AccessRequestService requests;
    private AccessReviewService reviews;
    private CacheVersionStore versions;

    private UUID tenantA;
    private UUID tenantB;
    private UUID actorA;
    private UUID actorB;
    private UUID personA;
    private UUID personB;
    private UUID fileB;
    private UUID jobB;
    private UUID assignmentB;
    private UUID reviewB;
    private UUID requestB;

    @Test
    void crossTenantAttackSuitePassesCompletely() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tenantTransactions = new TenantTransactions(jdbc, transactionTemplate);
        clock = Clock.systemUTC();
        ObjectMapper mapper = new ObjectMapper();

        TenantStore tenantStore = new TenantStore(jdbc, mapper);
        TenantLifecycleService lifecycle = new TenantLifecycleService(tenantTransactions,
                tenantStore, new SupportAccessStore(jdbc), clock, 30);

        String suffix = Long.toString(System.nanoTime(), 36);
        tenantA = createTenant(lifecycle, "ct-a-" + suffix, "跨租户相似租户");
        tenantB = createTenant(lifecycle, "ct-b-" + suffix, "跨租户相似租户");

        AuthorizationProperties properties = new AuthorizationProperties();
        AuthorizationStore store = new AuthorizationStore(jdbc);
        versions = new CacheVersionStore(jdbc, properties);
        AuthorizationCache cache = new AuthorizationCache(properties);
        OrganizationScopeQueries organizationScopes = new OrganizationScopeQueries(jdbc);
        MemberStore members = new MemberStore(jdbc);
        AuthorizationService authorization = new AuthorizationService(tenantTransactions, store,
                versions, cache, new DerivedGrantResolver(), new ScopeResolver(organizationScopes),
                new ConditionEvaluator(mapper), new FieldPolicyEvaluator(store), members,
                organizationScopes, new SodChecker(), (subject, request) -> false, clock, mapper);
        admin = new AuthorizationAdminService(tenantTransactions, store, cache, versions,
                new ScopeResolver(organizationScopes), new ConditionEvaluator(mapper),
                organizationScopes, members, new AccessRequestStore(jdbc), new AdminActionStore(jdbc),
                properties, mapper, clock);
        AuthorizationGuard guard = new AuthorizationGuard(authorization);
        requests = new AccessRequestService(tenantTransactions, new AccessRequestStore(jdbc), store,
                admin, guard, authorization, new AdminActionStore(jdbc), mapper, clock);
        reviews = new AccessReviewService(tenantTransactions, new AccessReviewStore(jdbc), store,
                organizationScopes, guard, authorization, versions, new AdminActionStore(jdbc),
                properties, mapper, clock);
        people = new PeopleService(tenantTransactions, new PersonStore(jdbc),
                new EmploymentStore(jdbc));

        ObjectStorageProperties storageProperties = new ObjectStorageProperties(
                "http://localhost:9000", "platform-test", "us-east-1", "key", "secret", null, null);
        OutboxEventPublisher publisher = new OutboxEventPublisher(jdbc, mapper, "verify");
        files = new FileService(new FileStore(jdbc), tenantTransactions, publisher,
                new S3Presigner(storageProperties), new ObjectStorageClient(storageProperties),
                storageProperties);
        jobs = new JobService(new JobStore(jdbc), tenantTransactions, publisher, mapper,
                new PlatformMetrics(new SimpleMeterRegistry()));

        seed();

        List<Case> cases = List.of(
                new Case("详情：A 读 B 的自然人", () ->
                        expectCode("PERSON_NOT_FOUND", () -> people.get(personB))),
                new Case("同名列表：A 只看到自己的张三", () -> {
                    List<PeopleService.DirectoryEntry> entries =
                            people.directory(DirectoryScope.tenant(null, false), "张三", 50);
                    assertThat(entries).hasSize(1);
                    assertThat(entries.getFirst().id()).isEqualTo(personA);
                }),
                new Case("同工号：A 读 B 的任职", () -> {
                    expectCode("PERSON_NOT_FOUND",
                            () -> people.employments(personB, DirectoryScope.tenant(null, false)));
                    Long count = asTenant(tenantA, () -> jdbc.queryForObject("""
                            SELECT count(*) FROM organization_employment
                             WHERE employment_no = 'E-0001'
                            """, Long.class));
                    assertThat(count).isEqualTo(1L);
                }),
                new Case("批量：A 批量读取 B 的 ID", () -> {
                    List<PeopleService.DirectoryEntry> entries = people.batchRead(
                            List.of(personB, personB), DirectoryScope.tenant(null, false));
                    assertThat(entries).isEmpty();
                }),
                new Case("导出：A 导出张三不含 B", () -> {
                    List<PeopleService.DirectoryEntry> rows =
                            people.export(DirectoryScope.tenant(null, false), "张三");
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().id()).isEqualTo(personA);
                }),
                new Case("附件列表：A 只看到自己的同名文件", () -> {
                    List<com.acme.platform.platform.file.FileRecord> rows = files.list(50);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().objectKey()).startsWith(tenantA + "/");
                }),
                new Case("附件详情：A 读 B 的文件", () ->
                        expectCode("FILE_NOT_FOUND", () -> files.find(fileB))),
                new Case("预签名：A 为 B 的文件取下载地址", () ->
                        expectCode("FILE_NOT_FOUND", () -> files.authorizeDownload(fileB))),
                new Case("对象路径：上传键必须带 A 前缀", () -> {
                    FileService.UploadSession session =
                            files.createUploadSession("合同.pdf", "application/pdf", 1024, null);
                    assertThat(session.objectKey()).startsWith(tenantA + "/");
                    assertThat(session.objectKey()).doesNotContain(tenantB.toString());
                }),
                new Case("缓存：两租户版本互不影响", () -> {
                    long a0 = asTenant(tenantA, () -> jdbc.queryForObject(
                            "SELECT version FROM authorization_cache_version", Long.class));
                    long b0 = asTenant(tenantB, () -> jdbc.queryForObject(
                            "SELECT version FROM authorization_cache_version", Long.class));
                    asTenant(tenantA, () -> jdbc.update("""
                            INSERT INTO authorization_assignment
                                (tenant_id, actor_id, role_code, scope_type, valid_from,
                                 reason, granted_by)
                            VALUES (?, ?, 'payroll-preparer', 'tenant', now() - interval '1 day',
                                    '版本隔离', 'verify')
                            """, tenantA, actorA));
                    long a1 = asTenant(tenantA, () -> jdbc.queryForObject(
                            "SELECT version FROM authorization_cache_version", Long.class));
                    long b1 = asTenant(tenantB, () -> jdbc.queryForObject(
                            "SELECT version FROM authorization_cache_version", Long.class));
                    assertThat(a1).isGreaterThan(a0);
                    assertThat(b1).isEqualTo(b0);
                }),
                new Case("作业：A 取消 B 的作业", () -> {
                    expectCode("JOB_NOT_FOUND", () -> jobs.cancel(jobB));
                    Boolean cancelled = asTenant(tenantB, () -> jdbc.queryForObject(
                            "SELECT cancel_requested FROM platform_job WHERE id = ?",
                            Boolean.class, jobB));
                    assertThat(cancelled).isFalse();
                }),
                new Case("Outbox：投递表按设计不启用 RLS，隔离靠显式 tenant_id", () -> {
                    Boolean rls = asTenant(tenantA, () -> jdbc.queryForObject("""
                            SELECT relrowsecurity FROM pg_class
                             WHERE oid = 'public.platform_outbox'::regclass
                            """, Boolean.class));
                    assertThat(rls).isFalse();
                    Long own = asTenant(tenantA, () -> jdbc.queryForObject("""
                            SELECT count(*) FROM platform_outbox WHERE tenant_id = ?
                            """, Long.class, tenantA));
                    assertThat(own).isEqualTo(1L);
                }),
                new Case("复核：A 读 B 的季度复核", () ->
                        expectCode("REVIEW_NOT_FOUND", () -> reviews.detail(reviewB))),
                new Case("申请：A 批准 B 的申请", () ->
                        expectCode("ACCESS_REQUEST_NOT_FOUND", () -> requests.approve(requestB, "同意"))),
                new Case("授权治理：A 撤销 B 的授权", () -> {
                    expectCode("ASSIGNMENT_NOT_FOUND", () -> admin.revokeAssignment(assignmentB));
                    Object revokedAt = asTenant(tenantB, () -> jdbc.queryForObject(
                            "SELECT revoked_at FROM authorization_assignment WHERE id = ?",
                            Object.class, assignmentB));
                    assertThat(revokedAt).isNull();
                }),
                new Case("审计：A 看不到 B 的管理员操作", () -> {
                    List<AdminActionStore.AdminActionRow> rows = admin.listAdminActions(200);
                    assertThat(rows).noneMatch(row -> "FIXTURE_B".equals(row.action()));
                }),
                new Case("元数据：A 与 B 的缓存版本行互斥", () -> {
                    Long visible = asTenant(tenantA, () -> jdbc.queryForObject("""
                            SELECT count(*) FROM authorization_cache_version WHERE tenant_id = ?
                            """, Long.class, tenantB));
                    assertThat(visible).isZero();
                }));

        List<String> failures = new ArrayList<>();
        for (Case testCase : cases) {
            try {
                as(tenantA, actorA, testCase.body());
            } catch (Throwable e) {
                failures.add(testCase.name() + " -> " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
        assertThat(failures).as("跨租户攻击用例失败 %d/%d", failures.size(), cases.size())
                .isEmpty();
        assertThat(cases).hasSize(17);
    }

    // ---------------------------------------------------------------- 夹具

    private UUID createTenant(TenantLifecycleService lifecycle, String slug, String name) {
        UUID id = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(id, slug, name,
                "standard", null, null, "zh-CN", "Asia/Shanghai", slug + ".example.test",
                List.of(), null), "ops");
        return id;
    }

    private void seed() {
        actorA = UUID.randomUUID();
        actorB = UUID.randomUUID();
        personA = UUID.randomUUID();
        personB = UUID.randomUUID();
        UUID personA2 = UUID.randomUUID();
        UUID personB2 = UUID.randomUUID();
        fileB = UUID.randomUUID();
        UUID fileA = UUID.randomUUID();
        jobB = UUID.randomUUID();
        assignmentB = UUID.randomUUID();
        reviewB = UUID.randomUUID();
        requestB = UUID.randomUUID();

        asTenant(tenantA, () -> {
            jdbc.update("""
                    INSERT INTO identity_actor (id, tenant_id, actor_type, display_name)
                    VALUES (?, ?, 'service', '跨租户攻击者')
                    """, actorA, tenantA);
            jdbc.update("""
                    INSERT INTO organization_person (id, tenant_id, display_name, primary_email)
                    VALUES (?, ?, '张三', 'zhangsan@same.example'),
                           (?, ?, '李四', 'lisi@same.example')
                    """, personA, tenantA, personA2, tenantA);
            jdbc.update("""
                    INSERT INTO organization_employment
                        (id, tenant_id, person_id, employment_no, effective_from)
                    VALUES (?, ?, ?, 'E-0001', DATE '2026-01-01'),
                           (?, ?, ?, 'E-0002', DATE '2026-01-01')
                    """, UUID.randomUUID(), tenantA, personA, UUID.randomUUID(), tenantA, personA2);
            jdbc.update("""
                    INSERT INTO authorization_assignment
                        (tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by)
                    VALUES (?, ?, 'manager', 'tenant', now() - interval '1 day',
                            '复核只读权限', 'verify')
                    """, tenantA, actorA);
            jdbc.update("""
                    INSERT INTO platform_file
                        (id, tenant_id, subject_id, file_name, content_type, size_bytes,
                         object_key, status, scan_status)
                    VALUES (?, ?, 'fixture', '合同.pdf', 'application/pdf', 1024, ?, 'available',
                            'clean')
                    """, fileA, tenantA, tenantA + "/" + fileA + "/合同.pdf");
            jdbc.update("""
                    INSERT INTO platform_outbox
                        (event_id, tenant_id, event_name, schema_version, producer, correlation_id,
                         trace_id, occurred_at, payload)
                    VALUES (?, ?, ?, 1, 'verify', 'corr-a', 'trace-a', now(), '{}'::jsonb)
                    """, UUID.randomUUID(), tenantA, FIXTURE_EVENT);
            return null;
        });

        asTenant(tenantB, () -> {
            jdbc.update("""
                    INSERT INTO identity_actor (id, tenant_id, actor_type, display_name)
                    VALUES (?, ?, 'service', '跨租户被攻击者')
                    """, actorB, tenantB);
            jdbc.update("""
                    INSERT INTO organization_person (id, tenant_id, display_name, primary_email)
                    VALUES (?, ?, '张三', 'zhangsan@same.example'),
                           (?, ?, '李四', 'lisi@same.example')
                    """, personB, tenantB, personB2, tenantB);
            jdbc.update("""
                    INSERT INTO organization_employment
                        (id, tenant_id, person_id, employment_no, effective_from)
                    VALUES (?, ?, ?, 'E-0001', DATE '2026-01-01'),
                           (?, ?, ?, 'E-0002', DATE '2026-01-01')
                    """, UUID.randomUUID(), tenantB, personB, UUID.randomUUID(), tenantB, personB2);
            jdbc.update("""
                    INSERT INTO authorization_assignment
                        (id, tenant_id, actor_id, role_code, scope_type, valid_from, reason, granted_by)
                    VALUES (?, ?, ?, 'employee', 'tenant', now() - interval '1 day',
                            '被攻击授权', 'verify')
                    """, assignmentB, tenantB, actorB);
            jdbc.update("""
                    INSERT INTO platform_file
                        (id, tenant_id, subject_id, file_name, content_type, size_bytes,
                         object_key, status, scan_status)
                    VALUES (?, ?, 'fixture', '合同.pdf', 'application/pdf', 1024, ?, 'available',
                            'clean')
                    """, fileB, tenantB, tenantB + "/" + fileB + "/合同.pdf");
            jdbc.update("""
                    INSERT INTO platform_job
                        (id, tenant_id, subject_id, job_type, trace_id, correlation_id)
                    VALUES (?, ?, 'fixture', 'payroll-export', 'trace-b', 'corr-b')
                    """, jobB, tenantB);
            jdbc.update("""
                    INSERT INTO platform_outbox
                        (event_id, tenant_id, event_name, schema_version, producer, correlation_id,
                         trace_id, occurred_at, payload)
                    VALUES (?, ?, ?, 1, 'verify', 'corr-b', 'trace-b', now(), '{}'::jsonb)
                    """, UUID.randomUUID(), tenantB, FIXTURE_EVENT);
            jdbc.update("""
                    INSERT INTO authorization_access_review
                        (id, tenant_id, period_start, period_end, due_at)
                    VALUES (?, ?, DATE '2026-10-01', DATE '2027-01-01', now() + interval '14 days')
                    """, reviewB, tenantB);
            jdbc.update("""
                    INSERT INTO authorization_access_review_item
                        (tenant_id, review_id, assignment_id, subject_actor_id)
                    VALUES (?, ?, ?, ?)
                    """, tenantB, reviewB, assignmentB, actorB);
            jdbc.update("""
                    INSERT INTO authorization_access_request
                        (id, tenant_id, requester_actor_id, requested_for_actor_id, role_code,
                         scope_type, reason)
                    VALUES (?, ?, ?, ?, 'employee', 'tenant', '被攻击申请')
                    """, requestB, tenantB, actorB, actorB);
            jdbc.update("""
                    INSERT INTO authorization_admin_action (tenant_id, actor_id, action, target_type)
                    VALUES (?, ?, 'FIXTURE_B', 'authorization.assignment')
                    """, tenantB, actorB);
            return null;
        });
    }

    // ---------------------------------------------------------------- 工具

    private record Case(String name, ThrowingRunnable body) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private void expectCode(String code, ThrowingRunnable action) {
        AtomicBoolean thrown = new AtomicBoolean(false);
        try {
            action.run();
        } catch (ApiException e) {
            thrown.set(true);
            assertThat(e.code()).isEqualTo(code);
        }
        assertThat(thrown).as("期望 %s，但调用成功", code).isTrue();
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

    private void as(UUID tenant, UUID actor, ThrowingRunnable action) {
        as(tenant, actor, () -> {
            action.run();
            return null;
        });
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
