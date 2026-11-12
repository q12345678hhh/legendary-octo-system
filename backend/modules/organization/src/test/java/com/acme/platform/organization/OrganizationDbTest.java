package com.acme.platform.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * 真实 PostgreSQL 上的组织结构闭环。
 *
 * <p>覆盖验收要求：三级汇报链、兼岗、代管、虚线与调动历史；
 * 部门树成环被拒绝、移动必须先出预览且票据只能消费一次；
 * 到期变更由 Worker 路径应用；空老板角色阻断启用；无上下文时租户表全部不可见。
 */
@EnabledIfSystemProperty(named = "platform.db.url", matches = ".+")
class OrganizationDbTest {

    private TenantTransactions tenantTransactions;

    @Test
    void organizationStructureLifecycle() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getProperty("platform.db.url"),
                System.getProperty("platform.db.user", "platform_app"),
                System.getProperty("platform.db.password", "platform_app"));
        dataSource.setDriverClassName("org.postgresql.Driver");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantTransactions transactions = new TenantTransactions(jdbc, transactionTemplate);
        this.tenantTransactions = transactions;
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        TenantStore tenantStore = new TenantStore(jdbc, mapper);
        TenantLifecycleService lifecycle = new TenantLifecycleService(transactions, tenantStore,
                new SupportAccessStore(jdbc), clock, 30);

        OrganizationStructureStore structureStore = new OrganizationStructureStore(jdbc);
        ImpactPreviewStore previewStore = new ImpactPreviewStore(jdbc);
        OrganizationHistoryStore historyStore = new OrganizationHistoryStore(jdbc);
        OrganizationCalendar calendar = new OrganizationCalendar(tenantStore, clock);
        OrganizationProperties properties = new OrganizationProperties();

        ImpactPreviewService previewService = new ImpactPreviewService(transactions, structureStore,
                previewStore, calendar, properties, clock);
        OrganizationStructureService structure = new OrganizationStructureService(transactions,
                structureStore, calendar, historyStore, previewService, mapper);

        AssignmentStore assignments = new AssignmentStore(jdbc);
        ReportingLineStore reportingLines = new ReportingLineStore(jdbc);
        DelegationStore delegations = new DelegationStore(jdbc);
        OwnerRoleStore ownerRoles = new OwnerRoleStore(jdbc);
        OrganizationRelationService relations = new OrganizationRelationService(transactions,
                new EmploymentStore(jdbc), structureStore, assignments, reportingLines, delegations,
                ownerRoles, calendar, properties, historyStore, mapper);

        OrganizationChangeStore changeStore = new OrganizationChangeStore(jdbc, mapper);
        OrganizationChangeService changes = new OrganizationChangeService(transactions, changeStore,
                structure, relations, previewService, calendar, historyStore);

        PeopleService people = new PeopleService(transactions, new PersonStore(jdbc),
                new EmploymentStore(jdbc));

        String suffix = Long.toString(System.nanoTime(), 36);
        UUID tenantC = UUID.randomUUID();
        UUID tenantD = UUID.randomUUID();
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantC, "org-c-" + suffix,
                "组织丙", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "org-c-" + suffix + ".example.test", List.of(), null), "ops");
        lifecycle.create(new TenantLifecycleService.CreateTenantCommand(tenantD, "org-d-" + suffix,
                "组织丁", "standard", null, null, "zh-CN", "Asia/Shanghai",
                "org-d-" + suffix + ".example.test", List.of(), null), "ops");
        lifecycle.initialize(tenantC, "ops");
        lifecycle.initialize(tenantD, "ops");

        LocalDate today = withContext(tenantC, () -> calendar.today(tenantC));

        // 1. 法人与三级部门链：总部 → 研发中心 → 平台组
        LegalEntity legalEntity = withContext(tenantC, () -> structure.createLegalEntity(
                new OrganizationStructureService.CreateLegalEntityCommand("LE-" + suffix, "示例法人", null)));
        OrgUnit company = createUnit(tenantC, structure, null, legalEntity.id(), "C-ROOT-" + suffix,
                "总部", OrgUnit.UnitType.COMPANY, 0);
        OrgUnit division = createUnit(tenantC, structure, company.id(), legalEntity.id(),
                "C-DIV-" + suffix, "研发中心", OrgUnit.UnitType.DIVISION, 0);
        OrgUnit department = createUnit(tenantC, structure, division.id(), legalEntity.id(),
                "C-DEPT-" + suffix, "平台组", OrgUnit.UnitType.DEPARTMENT, 0);
        assertThat(withContext(tenantC, () -> structure.unitTree())).hasSize(1);

        // 2. 职级、岗位与三个部门的职位编制
        JobLevel level = withContext(tenantC, () -> structure.createJobLevel(
                new OrganizationStructureService.CreateJobLevelCommand("L5-" + suffix, "高级", 5)));
        Job job = withContext(tenantC, () -> structure.createJob(
                new OrganizationStructureService.CreateJobCommand("BE-" + suffix, "后端工程师", "ENG",
                        level.id())));
        Position companyPosition = createPosition(tenantC, structure, company.id(), job.id(),
                "P-CMP-" + suffix, 10);
        Position divisionPosition = createPosition(tenantC, structure, division.id(), job.id(),
                "P-DIV-" + suffix, 5);
        Position departmentPosition = createPosition(tenantC, structure, department.id(), job.id(),
                "P-DEPT-" + suffix, 3);
        assertThat(withContext(tenantC, () -> structure.costCenters())).isEmpty();

        // 3. 三名员工：老板 → 研发负责人 → 平台组成员
        Person boss = createPerson(tenantC, people, "老板" + suffix, "boss-" + suffix + "@example.test",
                "E1-" + suffix);
        Person lead = createPerson(tenantC, people, "负责人" + suffix, "lead-" + suffix + "@example.test",
                "E2-" + suffix);
        Person member = createPerson(tenantC, people, "成员" + suffix, "member-" + suffix + "@example.test",
                "E3-" + suffix);
        Employment bossEmployment = firstEmployment(tenantC, people, boss.id());
        Employment leadEmployment = firstEmployment(tenantC, people, lead.id());
        Employment memberEmployment = firstEmployment(tenantC, people, member.id());

        // 4. 主岗 + 兼岗：成员主岗在平台组，同时兼岗在研发中心
        withContext(tenantC, () -> relations.assign(bossEmployment.id(),
                new OrganizationRelationService.AssignCommand(companyPosition.id(),
                        EmploymentAssignment.AssignmentType.PRIMARY, today.minusYears(2), null)));
        withContext(tenantC, () -> relations.assign(leadEmployment.id(),
                new OrganizationRelationService.AssignCommand(divisionPosition.id(),
                        EmploymentAssignment.AssignmentType.PRIMARY, today.minusYears(1), null)));
        withContext(tenantC, () -> relations.assign(memberEmployment.id(),
                new OrganizationRelationService.AssignCommand(departmentPosition.id(),
                        EmploymentAssignment.AssignmentType.PRIMARY, today.minusMonths(6), null)));
        EmploymentAssignment secondary = withContext(tenantC, () -> relations.assign(memberEmployment.id(),
                new OrganizationRelationService.AssignCommand(divisionPosition.id(),
                        EmploymentAssignment.AssignmentType.SECONDARY, today.minusMonths(6), null)));
        assertThat(secondary.assignmentType()).isEqualTo(EmploymentAssignment.AssignmentType.SECONDARY);
        assertThat(withContext(tenantC, () -> relations.currentAssignments(memberEmployment.id())))
                .hasSize(2);

        // 5. 三级实线汇报链与一条虚线
        withContext(tenantC, () -> relations.addReportingLine(
                new OrganizationRelationService.AddReportingLineCommand(leadEmployment.id(),
                        bossEmployment.id(), ReportingLine.LineType.SOLID, today.minusYears(1), null)));
        withContext(tenantC, () -> relations.addReportingLine(
                new OrganizationRelationService.AddReportingLineCommand(memberEmployment.id(),
                        leadEmployment.id(), ReportingLine.LineType.SOLID, today.minusMonths(6), null)));
        withContext(tenantC, () -> relations.addReportingLine(
                new OrganizationRelationService.AddReportingLineCommand(memberEmployment.id(),
                        bossEmployment.id(), ReportingLine.LineType.DOTTED, today.minusMonths(6), null)));
        assertThat(withContext(tenantC, () -> relations.reportingLines())).hasSize(3);

        // 成环：让老板汇报给自己的下属
        assertThatThrownBy(() -> withContext(tenantC, () -> relations.addReportingLine(
                new OrganizationRelationService.AddReportingLineCommand(bossEmployment.id(),
                        memberEmployment.id(), ReportingLine.LineType.DOTTED, today, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORTING_CYCLE"));

        // 6. 限时代管：老板休假期间把平台组交给成员代管
        Delegation delegation = withContext(tenantC, () -> relations.createDelegation(
                new OrganizationRelationService.CreateDelegationCommand(bossEmployment.id(),
                        memberEmployment.id(), department.id(), "年假期间代管", today.minusDays(1),
                        today.plusDays(30))));
        assertThat(withContext(tenantC, () -> relations.currentDelegations())).containsExactly(delegation);

        // 7. 老板与受托人：两类角色都必须独立存在
        withContext(tenantC, () -> relations.assignOwnerRole(
                new OrganizationRelationService.AssignOwnerRoleCommand(bossEmployment.id(),
                        OwnerRole.RoleType.OWNER, today.minusYears(2), null)));
        withContext(tenantC, () -> relations.assignOwnerRole(
                new OrganizationRelationService.AssignOwnerRoleCommand(memberEmployment.id(),
                        OwnerRole.RoleType.TRUSTEE, today.minusMonths(1), null)));
        OrganizationRelationService.ReadinessView readiness = withContext(tenantC, relations::readiness);
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.ownerCount()).isEqualTo(1);
        assertThat(readiness.trusteeCount()).isEqualTo(1);
        withContext(tenantC, () -> {
            relations.requireReady();
            return null;
        });

        // 8. 部门树成环被拒绝；移动必须先出预览，票据只能消费一次
        assertThatThrownBy(() -> withContext(tenantC, () -> structure.moveUnit(company.id(),
                new OrganizationStructureService.MoveUnitCommand(department.id(), null, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ORG_UNIT_CYCLE"));

        ImpactPreview preview = withContext(tenantC, () -> structure.previewMove(department.id(),
                company.id()));
        assertThat(preview.descendantCount()).isZero();
        assertThat(preview.employmentCount()).isEqualTo(1);
        assertThat(preview.positionCount()).isEqualTo(1);
        withContext(tenantC, () -> structure.moveUnit(department.id(),
                new OrganizationStructureService.MoveUnitCommand(company.id(), preview.id(), 1)));
        assertThat(withContext(tenantC, () -> structure.units()).stream()
                .filter(unit -> unit.id().equals(department.id())).findFirst().orElseThrow().parentId())
                .isEqualTo(company.id());
        assertThatThrownBy(() -> withContext(tenantC, () -> structure.moveUnit(department.id(),
                new OrganizationStructureService.MoveUnitCommand(division.id(), preview.id(), 0))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ORG_IMPACT_PREVIEW_CONSUMED"));

        // 9. 未来变更：排期、撤销，以及 Worker 到期的应用路径
        OrganizationChangeService.ChangeResult scheduled = withContext(tenantC, () -> changes.create(
                new OrganizationChangeService.CreateChangeCommand(OrganizationChange.ChangeType.UNIT_UPDATE,
                        department.id(), today.plusDays(3),
                        Map.of("name", "平台组（改）", "unitType", "department", "sortOrder", 0),
                        "调整部门名称")));
        assertThat(scheduled.appliedImmediately()).isFalse();
        assertThat(scheduled.change().status()).isEqualTo(OrganizationChange.ChangeStatus.SCHEDULED);

        OrganizationChangeService.ChangeResult cancelled = withContext(tenantC, () -> changes.create(
                new OrganizationChangeService.CreateChangeCommand(OrganizationChange.ChangeType.UNIT_UPDATE,
                        division.id(), today.plusDays(5),
                        Map.of("name", "研发中心（改）", "unitType", "division", "sortOrder", 0),
                        "稍后再改")));
        assertThat(withContext(tenantC, () -> changes.cancel(cancelled.change().id())).status())
                .isEqualTo(OrganizationChange.ChangeStatus.CANCELLED);

        // 模拟跨日：把变更与到期队列都推到今天，Worker 才能认领
        withContext(tenantC, () -> {
            jdbc.update("UPDATE organization_change SET effective_date = CURRENT_DATE WHERE id = ?",
                    scheduled.change().id());
            jdbc.update("UPDATE platform_organization_due SET effective_date = CURRENT_DATE "
                    + "WHERE change_id = ?", scheduled.change().id());
            return null;
        });
        assertThat(changes.applyDue(10)).isEqualTo(1);
        assertThat(withContext(tenantC, () -> changes.get(scheduled.change().id())).status())
                .isEqualTo(OrganizationChange.ChangeStatus.APPLIED);
        assertThat(withContext(tenantC, () -> structure.units()).stream()
                .filter(unit -> unit.id().equals(department.id())).findFirst().orElseThrow().name())
                .isEqualTo("平台组（改）");

        // 10. 立即生效的变更直接落库并标记 applied
        OrganizationChangeService.ChangeResult immediate = withContext(tenantC, () -> changes.create(
                new OrganizationChangeService.CreateChangeCommand(OrganizationChange.ChangeType.UNIT_UPDATE,
                        department.id(), today, Map.of("name", "平台组", "unitType", "department",
                                "sortOrder", 0), "改回原名")));
        assertThat(immediate.appliedImmediately()).isTrue();
        assertThat(immediate.change().status()).isEqualTo(OrganizationChange.ChangeStatus.APPLIED);

        // 11. 历史快照可读且不可修改
        assertThat(withContext(tenantC, () -> changes.history("org_unit", department.id())))
                .isNotEmpty();
        assertThatThrownBy(() -> withContext(tenantC, () -> {
            jdbc.update("UPDATE organization_change_history SET action = 'tampered'");
            return null;
        })).isInstanceOf(DataAccessException.class);

        // 12. 空老板角色阻断模块启用（另一个租户只有结构，没有 owner）
        assertThatThrownBy(() -> withContext(tenantD, () -> {
            relations.requireReady();
            return null;
        })).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("ORGANIZATION_NOT_READY"));

        // 13. 无上下文时 14 张组织表全部不可见
        for (String table : List.of("organization_legal_entity", "organization_unit",
                "organization_job_level", "organization_job", "organization_position",
                "organization_cost_center", "organization_location",
                "organization_employment_assignment", "organization_reporting_line",
                "organization_delegation", "organization_owner_role", "organization_change",
                "organization_change_history", "organization_impact_preview")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
                    .as(table).isZero();
        }
    }

    private OrgUnit createUnit(UUID tenantId, OrganizationStructureService structure, UUID parentId,
                               UUID legalEntityId, String code, String name, OrgUnit.UnitType type,
                               int sortOrder) {
        return withContext(tenantId, () -> structure.createUnit(
                new OrganizationStructureService.CreateUnitCommand(parentId, legalEntityId, code, name,
                        type, sortOrder)));
    }

    private Position createPosition(UUID tenantId, OrganizationStructureService structure,
                                    UUID orgUnitId, UUID jobId, String code, Integer headcount) {
        return withContext(tenantId, () -> structure.createPosition(
                new OrganizationStructureService.CreatePositionCommand(orgUnitId, jobId, code, null,
                        headcount)));
    }

    private Person createPerson(UUID tenantId, PeopleService people, String name, String email,
                                String employmentNo) {
        return withContext(tenantId, () -> people.create(new PeopleService.CreatePersonCommand(
                name, email, employmentNo, LocalDate.parse("2023-01-01"), "工程师")));
    }

    private Employment firstEmployment(UUID tenantId, PeopleService people, UUID personId) {
        return withContext(tenantId, () ->
                people.employments(personId, DirectoryScope.tenant(personId, false))).getFirst();
    }

    private <T> T withContext(UUID tenantId, java.util.function.Supplier<T> action) {
        RequestContext context = new RequestContext(tenantId, null, UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""));
        RequestContextHolder.set(context);
        ActorContextHolder.set(new ActorContext(tenantId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "user", true, UUID.randomUUID()));
        try {
            return tenantTransactions.inTenant(context, ctx -> action.get());
        } finally {
            RequestContextHolder.clear();
            ActorContextHolder.clear();
        }
    }

    private void withContext(UUID tenantId, Runnable action) {
        withContext(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
