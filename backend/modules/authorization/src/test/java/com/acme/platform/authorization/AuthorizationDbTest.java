package com.acme.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.platform.identity.MemberStore;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ActorContextHolder;
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
 * 真实 PostgreSQL 上的统一授权闭环。
 *
 * <p>覆盖：缺租户/缺主体/未知权限默认拒绝、员工与主管范围、显式拒绝优先、
 * 临时授权到期、委托子树、自定义角色、缓存版本递增与跨租户隔离。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class AuthorizationDbTest {

    private TenantTransactions tenantTransactions;
    private JdbcTemplate jdbc;
    private AuthorizationService service;
    private AuthorizationAdminService admin;
    private CacheVersionStore versions;

    private record Fixture(UUID tenantId, UUID actorId, UUID userId, UUID memberId, UUID personId) {
    }

    @Test
    void authorizationLifecycleAcrossScenarios() {
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
        TenantLifecycleService lifecycle = new TenantLifecycleService(tenantTransactions, tenantStore,
                new SupportAccessStore(jdbc), clock, 30);
        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantA, "authz-a-" + suffix,
                "授权甲", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "authz-a-" + suffix + ".example.test", List.of(), null), "ops");
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantB, "authz-b-" + suffix,
                "授权乙", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "authz-b-" + suffix + ".example.test", List.of(), null), "ops");

        AuditFixture fixtures = seedOrganization(tenantA, suffix);
        Fixture manager = fixtures.manager();
        Fixture employee = fixtures.employee();
        Fixture delegate = fixtures.delegate();
        Fixture outsider = fixtures.outsider();
        UUID otherPersonId = fixtures.outsider().personId();

        AuthorizationStore store = new AuthorizationStore(jdbc);
        versions = new CacheVersionStore(jdbc, new AuthorizationProperties());
        AuthorizationCache cache = new AuthorizationCache(new AuthorizationProperties());
        OrganizationScopeQueries organizationScopes = new OrganizationScopeQueries(jdbc);
        AuthorizationProperties properties = new AuthorizationProperties();
        service = new AuthorizationService(tenantTransactions, store, versions, cache,
                new DerivedGrantResolver(), new ScopeResolver(organizationScopes),
                new ConditionEvaluator(mapper), new FieldPolicyEvaluator(store),
                new MemberStore(jdbc), organizationScopes,
                (subject, permission) -> false, (subject, request) -> false,
                clock, mapper);
        admin = new AuthorizationAdminService(tenantTransactions, store, cache, versions,
                new ScopeResolver(organizationScopes), new ConditionEvaluator(mapper),
                organizationScopes, new MemberStore(jdbc), properties, clock);

        // 1. 缺主体、缺租户、未知权限：默认拒绝
        AccessDecision noSubject = as(manager, () -> service.check(new AccessRequest(tenantA, null,
                "directory.person.read", "directory.person", null, Map.of())));
        assertThat(noSubject.allowed()).isFalse();
        assertThat(noSubject.reason()).isEqualTo(DecisionReason.SUBJECT_MISSING);

        AccessDecision wrongTenant = as(manager, () -> service.check(new AccessRequest(tenantB,
                manager.actorId(), "directory.person.read", "directory.person", null, Map.of())));
        assertThat(wrongTenant.allowed()).isFalse();
        assertThat(wrongTenant.reason()).isEqualTo(DecisionReason.TENANT_MISSING);

        AccessDecision unknown = as(manager, () -> service.check(new AccessRequest(tenantA,
                manager.actorId(), "directory.person.destroy", "directory.person", null, Map.of())));
        assertThat(unknown.allowed()).isFalse();
        assertThat(unknown.reason()).isEqualTo(DecisionReason.PERMISSION_UNKNOWN);

        // 2. 员工：本人 + 本部门目录；部门外的人不可见
        AccessDecision employeeRead = as(employee, () -> service.check(new AccessRequest(tenantA,
                employee.actorId(), "directory.person.read", "directory.person", null, Map.of())));
        assertThat(employeeRead.allowed()).isTrue();
        assertThat(employeeRead.scope().tenantWide()).isFalse();
        assertThat(employeeRead.scope().includesPerson(employee.personId())).isTrue();

        AccessDecision employeeOutside = as(employee, () -> service.check(new AccessRequest(tenantA,
                employee.actorId(), "directory.person.read", "directory.person", otherPersonId,
                Map.of("personId", otherPersonId))));
        assertThat(employeeOutside.allowed()).isFalse();
        assertThat(employeeOutside.reason()).isEqualTo(DecisionReason.SCOPE_MISS);

        // 3. 主管：直接下属可见，并带汇报链因素
        AccessDecision managerReport = as(manager, () -> service.check(new AccessRequest(tenantA,
                manager.actorId(), "directory.person.read", "directory.person", employee.personId(),
                Map.of("personId", employee.personId()))));
        assertThat(managerReport.allowed()).isTrue();
        assertThat(managerReport.factors()).anyMatch(factor -> factor.contains("MANAGER"));

        // 4. 显式拒绝优先于允许
        as(manager, () -> admin.addDeny(new AuthorizationAdminService.DenyCommand(employee.actorId(),
                null, "directory.person.export", "directory.person", null, null, null,
                "员工禁止导出", null)));
        AccessDecision denied = as(employee, () -> service.check(new AccessRequest(tenantA,
                employee.actorId(), "directory.person.export", "directory.person", null, Map.of())));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo(DecisionReason.EXPLICIT_DENY);

        // 5. 自定义角色 + 临时授权到期
        as(manager, () -> admin.createRole(new AuthorizationAdminService.CreateRoleCommand(
                "temp-auditor", "临时审计员", "只读报表", java.util.Set.of("report.export"))));
        UUID activeGrant = as(manager, () -> admin.grant(
                new AuthorizationAdminService.GrantCommand(employee.actorId(),
                        employee.memberId(), "temp-auditor", "org_unit", fixtures.employeeUnitId(),
                        OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1),
                        "临时审计", null)).id());
        AccessDecision temporary = as(employee, () -> service.check(new AccessRequest(tenantA,
                employee.actorId(), "report.export", "report", null, Map.of())));
        assertThat(temporary.allowed()).isTrue();

        asTenant(tenantA, () -> jdbc.update("""
                INSERT INTO authorization_assignment
                    (tenant_id, actor_id, member_id, role_code, scope_type, scope_id,
                     valid_from, valid_to, reason, granted_by)
                VALUES (?, ?, ?, 'temp-auditor', 'org_unit', ?, ?, ?, '过期临时授权', 'verify')
                """, tenantA, employee.actorId(), employee.memberId(), fixtures.employeeUnitId(),
                OffsetDateTime.now().minusDays(10), OffsetDateTime.now().minusDays(2)));
        as(manager, () -> {
            admin.revokeAssignment(activeGrant);
            return null;
        });
        AccessDecision expired = as(employee, () -> service.check(new AccessRequest(tenantA,
                employee.actorId(), "report.export", "report", null, Map.of())));
        assertThat(expired.allowed()).isFalse();
        assertThat(expired.reason()).isEqualTo(DecisionReason.NO_ALLOW);

        // 6. 委托：代管人按委派子树看到下属部门的人
        AccessDecision delegated = as(delegate, () -> service.check(new AccessRequest(tenantA,
                delegate.actorId(), "directory.person.read", "directory.person", employee.personId(),
                Map.of("personId", employee.personId()))));
        assertThat(delegated.allowed()).isTrue();

        // 7. 缓存命中与组织变更后的版本递增
        AccessDecision first = as(manager, () -> service.check(new AccessRequest(tenantA,
                manager.actorId(), "directory.person.read", "directory.person", null, Map.of())));
        AccessDecision second = as(manager, () -> service.check(new AccessRequest(tenantA,
                manager.actorId(), "directory.person.read", "directory.person", null, Map.of())));
        assertThat(first.allowed()).isTrue();
        assertThat(second.cacheHit()).isTrue();

        long versionBefore = asTenant(tenantA, () -> {
            versions.invalidateLocal();
            return service.currentVersion();
        });
        asTenant(tenantA, () -> jdbc.update(
                "UPDATE organization_unit SET name = name || '（授权）' WHERE id = ?",
                fixtures.employeeUnitId()));
        long versionAfter = asTenant(tenantA, () -> {
            versions.invalidateLocal();
            return service.currentVersion();
        });
        assertThat(versionAfter).isGreaterThan(versionBefore);
        AccessDecision afterChange = as(manager, () -> service.check(new AccessRequest(tenantA,
                manager.actorId(), "directory.person.read", "directory.person", null, Map.of())));
        assertThat(afterChange.cacheHit()).isFalse();

        // 8. 解释器与审计日志
        AccessDecision explained = as(manager, () -> service.explain(new AccessRequest(tenantA,
                manager.actorId(), "directory.person.read", "directory.person", null, Map.of())));
        assertThat(explained.allowed()).isTrue();
        assertThat(explained.factors()).anyMatch(factor -> factor.startsWith("GRANT:"));
        assertThat(asTenant(tenantA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM authorization_decision_log", Long.class)))
                .isGreaterThan(0);

        // 9. 跨租户隔离：乙租户看不到甲的授权，主体在乙也不存在
        RequestContextHolder.set(new RequestContext(tenantB, outsider.actorId().toString(),
                UUID.randomUUID().toString(), "t"));
        ActorContextHolder.clear();
        try {
            AccessDecision crossTenant = service.check(new AccessRequest(tenantB,
                    manager.actorId(), "directory.person.read", "directory.person", null, Map.of()));
            assertThat(crossTenant.allowed()).isFalse();
            assertThat(crossTenant.reason()).isEqualTo(DecisionReason.SUBJECT_MISSING);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM authorization_assignment", Long.class))
                    .isZero();
        } finally {
            RequestContextHolder.clear();
        }
    }

    private AuditFixture seedOrganization(UUID tenant, String suffix) {
        UUID legalEntity = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        UUID employeeUnit = UUID.randomUUID();
        UUID otherUnit = UUID.randomUUID();
        UUID jobLevel = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID employeePosition = UUID.randomUUID();

        Fixture manager = createActor(tenant, suffix + "-mgr", true, true);
        Fixture employee = createActor(tenant, suffix + "-emp", false, false);
        Fixture delegate = createActor(tenant, suffix + "-dlg", false, false);
        Fixture outsider = createActor(tenant, suffix + "-out", false, false);

        asTenant(tenant, () -> {
            jdbc.update("""
                    INSERT INTO organization_legal_entity (id, tenant_id, code, name, registration_no)
                    VALUES (?, ?, ?, '授权测试法人', ?)
                    """, legalEntity, tenant, "LE-" + suffix, "91310000" + suffix);
            jdbc.update("""
                    INSERT INTO organization_unit (id, tenant_id, parent_id, legal_entity_id, code,
                                                   name, unit_type, sort_order)
                    VALUES (?, ?, NULL, ?, ?, '总部', 'company', 0),
                           (?, ?, ?, ?, ?, '研发部', 'department', 1),
                           (?, ?, ?, ?, ?, '其他部', 'department', 2)
                    """, root, tenant, legalEntity, "U-ROOT-" + suffix,
                    employeeUnit, tenant, root, legalEntity, "U-RD-" + suffix,
                    otherUnit, tenant, root, legalEntity, "U-OTHER-" + suffix);
            jdbc.update("""
                    INSERT INTO organization_job_level (id, tenant_id, code, name, rank)
                    VALUES (?, ?, 'L5', '高级', 5)
                    """, jobLevel, tenant);
            jdbc.update("""
                    INSERT INTO organization_job (id, tenant_id, code, name, job_family, default_level_id)
                    VALUES (?, ?, ?, '工程师', '研发', ?)
                    """, job, tenant, "J-" + suffix, jobLevel);
            jdbc.update("""
                    INSERT INTO organization_position (id, tenant_id, org_unit_id, job_id, code, name,
                                                       headcount_limit)
                    VALUES (?, ?, ?, ?, ?, '工程师', 5)
                    """, employeePosition, tenant, employeeUnit, job, "P-" + suffix);
            jdbc.update("""
                    INSERT INTO organization_employment_assignment
                        (tenant_id, employment_id, position_id, assignment_type, effective_from)
                    VALUES (?, ?, ?, 'primary', CURRENT_DATE - 10)
                    """, tenant, employmentOf(tenant, employee.personId()), employeePosition);
            jdbc.update("""
                    INSERT INTO organization_reporting_line
                        (tenant_id, subordinate_employment_id, manager_employment_id, line_type,
                         effective_from, created_by)
                    VALUES (?, ?, ?, 'solid', CURRENT_DATE - 10, 'verify')
                    """, tenant, employmentOf(tenant, employee.personId()),
                    employmentOf(tenant, manager.personId()));
            jdbc.update("""
                    INSERT INTO organization_delegation
                        (tenant_id, delegator_employment_id, delegate_employment_id,
                         scope_org_unit_id, reason, effective_from, effective_to, created_by)
                    VALUES (?, ?, ?, ?, '授权代管', CURRENT_DATE - 1, CURRENT_DATE + 30, 'verify')
                    """, tenant, employmentOf(tenant, manager.personId()),
                    employmentOf(tenant, delegate.personId()), employeeUnit);
            return null;
        });
        return new AuditFixture(manager, employee, delegate, outsider, employeeUnit, otherUnit);
    }

    private record AuditFixture(Fixture manager, Fixture employee, Fixture delegate, Fixture outsider,
                                UUID employeeUnitId, UUID otherUnitId) {
    }

    private Fixture createActor(UUID tenant, String label, boolean owner, boolean admin) {
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID employmentId = UUID.randomUUID();
        asTenant(tenant, () -> {
            jdbc.update("""
                    INSERT INTO organization_person (id, tenant_id, display_name)
                    VALUES (?, ?, ?)
                    """, personId, tenant, label);
            jdbc.update("""
                    INSERT INTO identity_user_account
                        (id, tenant_id, email, email_normalized, display_name, status)
                    VALUES (?, ?, ?, ?, ?, 'active')
                    """, userId, tenant, label + "@example.test", label + "@example.test", label);
            jdbc.update("""
                    INSERT INTO identity_tenant_member
                        (id, tenant_id, user_id, person_id, status, is_owner, is_admin)
                    VALUES (?, ?, ?, ?, 'active', ?, ?)
                    """, memberId, tenant, userId, personId, owner, admin);
            jdbc.update("""
                    INSERT INTO identity_actor (id, tenant_id, actor_type, display_name, user_id)
                    VALUES (?, ?, 'user', ?, ?)
                    """, actorId, tenant, label, userId);
            jdbc.update("""
                    INSERT INTO organization_employment
                        (id, tenant_id, person_id, employment_no, effective_from, job_title)
                    VALUES (?, ?, ?, ?, CURRENT_DATE - 10, '员工')
                    """, employmentId, tenant, personId, "E-" + label);
            return null;
        });
        return new Fixture(tenant, actorId, userId, memberId, personId);
    }

    private UUID employmentOf(UUID tenant, UUID personId) {
        return jdbc.queryForObject("""
                SELECT id FROM organization_employment WHERE person_id = ? ORDER BY effective_from DESC LIMIT 1
                """, UUID.class, personId);
    }

    private <T> T as(Fixture fixture, Supplier<T> action) {
        RequestContextHolder.set(new RequestContext(fixture.tenantId(), fixture.actorId().toString(),
                UUID.randomUUID().toString(), "t"));
        ActorContextHolder.set(new ActorContext(fixture.tenantId(), fixture.actorId(),
                fixture.userId(), fixture.memberId(), "user", true, null));
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
