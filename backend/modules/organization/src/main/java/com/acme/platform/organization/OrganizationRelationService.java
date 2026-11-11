package com.acme.platform.organization;

import com.acme.platform.platform.ApiException;
import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 组织关系应用服务：主/兼岗、汇报线、管理委派、老板与受托人。
 *
 * <p>关系实体自带生效区间：未来生效的行可以提前落库，查询时按租户业务日过滤；
 * 区间重叠、汇报道成环、委派超期都在写入前拒绝，数据库约束再做最后一道兜底。
 */
@Service
public class OrganizationRelationService {

    private final TenantTransactions tenantTransactions;
    private final EmploymentStore employments;
    private final OrganizationStructureStore structure;
    private final AssignmentStore assignments;
    private final ReportingLineStore reportingLines;
    private final DelegationStore delegations;
    private final OwnerRoleStore ownerRoles;
    private final OrganizationCalendar calendar;
    private final OrganizationProperties properties;
    private final OrganizationHistoryStore history;
    private final ObjectMapper objectMapper;

    public OrganizationRelationService(TenantTransactions tenantTransactions,
                                       EmploymentStore employments,
                                       OrganizationStructureStore structure,
                                       AssignmentStore assignments,
                                       ReportingLineStore reportingLines,
                                       DelegationStore delegations,
                                       OwnerRoleStore ownerRoles,
                                       OrganizationCalendar calendar,
                                       OrganizationProperties properties,
                                       OrganizationHistoryStore history,
                                       ObjectMapper objectMapper) {
        this.tenantTransactions = tenantTransactions;
        this.employments = employments;
        this.structure = structure;
        this.assignments = assignments;
        this.reportingLines = reportingLines;
        this.delegations = delegations;
        this.ownerRoles = ownerRoles;
        this.calendar = calendar;
        this.properties = properties;
        this.history = history;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 主/兼岗

    public List<EmploymentAssignment> assignments(UUID employmentId) {
        return tenantTransactions.inTenant(ctx -> {
            requireEmployment(employmentId);
            return assignments.listByEmployment(employmentId);
        });
    }

    public List<EmploymentAssignment> currentAssignments(UUID employmentId) {
        return tenantTransactions.inTenant(ctx -> {
            Employment employment = requireEmployment(employmentId);
            return assignments.listCurrentByEmployment(employmentId, calendar.today(employment.tenantId()));
        });
    }

    public EmploymentAssignment assign(UUID employmentId, AssignCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Employment employment = requireEmployment(employmentId);
            if (employment.status() != EmploymentStatus.ACTIVE) {
                throw ApiException.unprocessable("EMPLOYMENT_NOT_ACTIVE", "只有在职雇佣可以分配岗位");
            }
            Position position = structure.findPosition(command.positionId()).orElseThrow(() ->
                    ApiException.unprocessable("POSITION_NOT_FOUND", "职位不存在：" + command.positionId()));
            OrgUnit unit = structure.findUnit(position.orgUnitId()).orElseThrow(() ->
                    ApiException.unprocessable("ORG_UNIT_NOT_FOUND", "职位所属部门不存在"));
            if (unit.status() == OrgUnit.UnitStatus.ARCHIVED) {
                throw ApiException.unprocessable("POSITION_UNIT_ARCHIVED", "不能分配到已归档部门的职位");
            }
            requirePeriod(command.effectiveFrom(), command.effectiveTo());

            if (command.assignmentType() == EmploymentAssignment.AssignmentType.PRIMARY) {
                assignments.findOpenPrimary(employmentId).ifPresent(open -> {
                    if (open.effectiveFrom().isBefore(command.effectiveFrom())) {
                        // 调动：把现任主岗截止到新主岗生效前一天（左闭右开，截止日即为新起点）
                        assignments.closePrimary(open.id(), command.effectiveFrom());
                    } else {
                        throw ApiException.unprocessable("ASSIGNMENT_OVERLAP",
                                "已存在同等或更晚生效的未结束主岗：" + open.id());
                    }
                });
                if (assignments.hasOverlappingPrimary(employmentId, command.effectiveFrom(),
                        command.effectiveTo())) {
                    throw ApiException.unprocessable("ASSIGNMENT_OVERLAP", "该雇佣在所选区间已有主岗");
                }
            }

            UUID id = UUID.randomUUID();
            assignments.insert(id, employmentId, command.positionId(), command.assignmentType(),
                    command.effectiveFrom(), command.effectiveTo());
            EmploymentAssignment created = assignments.find(id).orElseThrow();
            history.insert(null, "employment_assignment", id, "create",
                    calendar.today(employment.tenantId()), null, json(created),
                    OrganizationStructureService.actor());
            return created;
        });
    }

    public EmploymentAssignment endAssignment(UUID id, LocalDate effectiveTo) {
        return tenantTransactions.inTenant(ctx -> {
            EmploymentAssignment before = assignments.find(id).orElseThrow(() ->
                    ApiException.notFound("ASSIGNMENT_NOT_FOUND", "岗位分配不存在：" + id));
            if (effectiveTo == null || !effectiveTo.isAfter(before.effectiveFrom())) {
                throw ApiException.unprocessable("ASSIGNMENT_PERIOD_INVALID",
                        "结束日期必须晚于生效日期");
            }
            if (assignments.end(id, effectiveTo) == 0) {
                throw ApiException.conflict("ASSIGNMENT_ALREADY_ENDED", "岗位分配已经结束");
            }
            EmploymentAssignment after = assignments.find(id).orElseThrow();
            history.insert(null, "employment_assignment", id, "end",
                    calendar.today(before.tenantId()), json(before), json(after),
                    OrganizationStructureService.actor());
            return after;
        });
    }

    // ---------------------------------------------------------------- 汇报线

    public List<ReportingLine> reportingLines() {
        return tenantTransactions.inTenant(ctx -> {
            UUID tenantId = com.acme.platform.platform.RequestContextHolder.current().tenantId();
            return reportingLines.listCurrent(calendar.today(tenantId));
        });
    }

    public List<ReportingLine> reportingLinesOf(UUID employmentId) {
        return tenantTransactions.inTenant(ctx -> {
            requireEmployment(employmentId);
            return reportingLines.listBySubordinate(employmentId);
        });
    }

    public ReportingLine addReportingLine(AddReportingLineCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Employment subordinate = requireEmployment(command.subordinateEmploymentId());
            requireEmployment(command.managerEmploymentId());
            requirePeriod(command.effectiveFrom(), command.effectiveTo());
            LocalDate today = calendar.today(subordinate.tenantId());
            ReportingRules.ensureNoCycle(command.subordinateEmploymentId(), command.managerEmploymentId(),
                    employmentId -> reportingLines.managersOf(employmentId, today));
            if (command.lineType() == ReportingLine.LineType.SOLID
                    && command.effectiveTo() == null
                    && reportingLines.hasOpenSolid(command.subordinateEmploymentId())) {
                throw ApiException.conflict("REPORTING_SOLID_TAKEN",
                        "该员工已有一条未结束的实线汇报，请先结束旧的实线");
            }
            UUID id = UUID.randomUUID();
            reportingLines.insert(id, command.subordinateEmploymentId(),
                    command.managerEmploymentId(), command.lineType(), command.effectiveFrom(),
                    command.effectiveTo(), OrganizationStructureService.actor());
            ReportingLine created = reportingLines.find(id).orElseThrow();
            history.insert(null, "reporting_line", id, "create", today, null, json(created),
                    OrganizationStructureService.actor());
            return created;
        });
    }

    public ReportingLine endReportingLine(UUID id, LocalDate effectiveTo) {
        return tenantTransactions.inTenant(ctx -> {
            ReportingLine before = reportingLines.find(id).orElseThrow(() ->
                    ApiException.notFound("REPORTING_LINE_NOT_FOUND", "汇报线不存在：" + id));
            if (effectiveTo == null || !effectiveTo.isAfter(before.effectiveFrom())) {
                throw ApiException.unprocessable("REPORTING_PERIOD_INVALID", "结束日期必须晚于生效日期");
            }
            if (reportingLines.end(id, effectiveTo) == 0) {
                throw ApiException.conflict("REPORTING_LINE_ALREADY_ENDED", "汇报线已经结束");
            }
            ReportingLine after = reportingLines.find(id).orElseThrow();
            history.insert(null, "reporting_line", id, "end",
                    calendar.today(before.tenantId()), json(before), json(after),
                    OrganizationStructureService.actor());
            return after;
        });
    }

    // ---------------------------------------------------------------- 管理委派

    public List<Delegation> delegations() {
        return tenantTransactions.inTenant(ctx -> delegations.list());
    }

    public List<Delegation> currentDelegations() {
        return tenantTransactions.inTenant(ctx ->
                delegations.listCurrent(calendar.today(
                        com.acme.platform.platform.RequestContextHolder.current().tenantId())));
    }

    public Delegation createDelegation(CreateDelegationCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Employment delegator = requireEmployment(command.delegatorEmploymentId());
            requireEmployment(command.delegateEmploymentId());
            if (command.delegatorEmploymentId().equals(command.delegateEmploymentId())) {
                throw ApiException.unprocessable("DELEGATION_SELF", "不能把管理职责委派给自己");
            }
            if (command.reason() == null || command.reason().isBlank()) {
                throw ApiException.unprocessable("DELEGATION_REASON_REQUIRED", "代管必须填写原因");
            }
            OrgUnit scope = structure.findUnit(command.scopeOrgUnitId()).orElseThrow(() ->
                    ApiException.unprocessable("ORG_UNIT_NOT_FOUND",
                            "委派范围部门不存在：" + command.scopeOrgUnitId()));
            if (scope.status() == OrgUnit.UnitStatus.ARCHIVED) {
                throw ApiException.unprocessable("ORG_UNIT_ARCHIVED", "不能把已归档部门设为委派范围");
            }
            if (command.effectiveTo() == null) {
                throw ApiException.unprocessable("DELEGATION_PERIOD_INVALID", "代管必须设置结束日期");
            }
            if (!command.effectiveTo().isAfter(command.effectiveFrom())) {
                throw ApiException.unprocessable("DELEGATION_PERIOD_INVALID", "结束日期必须晚于开始日期");
            }
            long days = ChronoUnit.DAYS.between(command.effectiveFrom(), command.effectiveTo());
            if (days > properties.getMaxDelegationDays()) {
                throw ApiException.unprocessable("DELEGATION_PERIOD_INVALID",
                        "代管最长 " + properties.getMaxDelegationDays() + " 天，本次 " + days + " 天");
            }
            UUID id = UUID.randomUUID();
            delegations.insert(id, command.delegatorEmploymentId(), command.delegateEmploymentId(),
                    command.scopeOrgUnitId(), command.reason().trim(), command.effectiveFrom(),
                    command.effectiveTo(), OrganizationStructureService.actor());
            Delegation created = delegations.find(id).orElseThrow();
            history.insert(null, "delegation", id, "create",
                    calendar.today(delegator.tenantId()), null, json(created),
                    OrganizationStructureService.actor());
            return created;
        });
    }

    public Delegation endDelegation(UUID id, LocalDate effectiveTo) {
        return tenantTransactions.inTenant(ctx -> {
            Delegation before = delegations.find(id).orElseThrow(() ->
                    ApiException.notFound("DELEGATION_NOT_FOUND", "代管记录不存在：" + id));
            if (effectiveTo == null || !effectiveTo.isAfter(before.effectiveFrom())) {
                throw ApiException.unprocessable("DELEGATION_PERIOD_INVALID", "结束日期必须晚于开始日期");
            }
            if (delegations.end(id, effectiveTo) == 0) {
                throw ApiException.conflict("DELEGATION_ALREADY_ENDED", "代管已经结束");
            }
            Delegation after = delegations.find(id).orElseThrow();
            history.insert(null, "delegation", id, "end",
                    calendar.today(before.tenantId()), json(before), json(after),
                    OrganizationStructureService.actor());
            return after;
        });
    }

    // ---------------------------------------------------------------- 老板 / 受托人

    public List<OwnerRole> ownerRoles() {
        return tenantTransactions.inTenant(ctx -> ownerRoles.list());
    }

    public OwnerRole assignOwnerRole(AssignOwnerRoleCommand command) {
        return tenantTransactions.inTenant(ctx -> {
            Employment employment = requireEmployment(command.employmentId());
            requirePeriod(command.effectiveFrom(), command.effectiveTo());
            ownerRoles.findOpen(command.employmentId(), command.roleType()).ifPresent(open -> {
                throw ApiException.conflict("OWNER_ROLE_ALREADY_ACTIVE",
                        "该员工已有生效中的" + command.roleType().wire() + "角色");
            });
            UUID id = UUID.randomUUID();
            ownerRoles.insert(id, command.employmentId(), command.roleType(),
                    command.effectiveFrom(), command.effectiveTo(),
                    OrganizationStructureService.actor());
            OwnerRole created = ownerRoles.find(id).orElseThrow();
            history.insert(null, "owner_role", id, "create",
                    calendar.today(employment.tenantId()), null, json(created),
                    OrganizationStructureService.actor());
            return created;
        });
    }

    public OwnerRole revokeOwnerRole(UUID id, LocalDate effectiveTo) {
        return tenantTransactions.inTenant(ctx -> {
            OwnerRole before = ownerRoles.find(id).orElseThrow(() ->
                    ApiException.notFound("OWNER_ROLE_NOT_FOUND", "角色记录不存在：" + id));
            if (effectiveTo == null || !effectiveTo.isAfter(before.effectiveFrom())) {
                throw ApiException.unprocessable("OWNER_ROLE_PERIOD_INVALID", "结束日期必须晚于生效日期");
            }
            if (ownerRoles.end(id, effectiveTo) == 0) {
                throw ApiException.conflict("OWNER_ROLE_ALREADY_ENDED", "角色已经结束");
            }
            OwnerRole after = ownerRoles.find(id).orElseThrow();
            history.insert(null, "owner_role", id, "revoke",
                    calendar.today(before.tenantId()), json(before), json(after),
                    OrganizationStructureService.actor());
            return after;
        });
    }

    public ReadinessView readiness() {
        return tenantTransactions.inTenant(ctx -> {
            UUID tenantId = com.acme.platform.platform.RequestContextHolder.current().tenantId();
            LocalDate today = calendar.today(tenantId);
            List<OwnerRole> current = ownerRoles.listCurrent(today);
            Set<UUID> owners = new java.util.LinkedHashSet<>();
            Set<UUID> trustees = new java.util.LinkedHashSet<>();
            for (OwnerRole role : current) {
                if (role.roleType() == OwnerRole.RoleType.OWNER) {
                    owners.add(role.employmentId());
                } else {
                    trustees.add(role.employmentId());
                }
            }
            OrganizationReadiness readiness = OrganizationReadiness.evaluate(owners, trustees);
            return new ReadinessView(readiness.ready(), readiness.blockers(), current,
                    owners.size(), trustees.size());
        });
    }

    /** 模块启用前的硬门禁：老板角色为空时直接失败，不提供「先启用后补」的开关。 */
    public void requireReady() {
        ReadinessView view = readiness();
        if (!view.ready()) {
            throw ApiException.conflict("ORGANIZATION_NOT_READY",
                    "组织尚未就绪，缺少：" + view.blockers());
        }
    }

    private Employment requireEmployment(UUID employmentId) {
        return employments.find(employmentId).orElseThrow(() ->
                ApiException.notFound("EMPLOYMENT_NOT_FOUND", "雇佣关系不存在：" + employmentId));
    }

    private void requirePeriod(LocalDate from, LocalDate to) {
        if (from == null) {
            throw ApiException.unprocessable("EFFECTIVE_DATE_REQUIRED", "必须提供生效日期");
        }
        if (to != null && !to.isAfter(from)) {
            throw ApiException.unprocessable("EFFECTIVE_PERIOD_INVALID", "结束日期必须晚于生效日期");
        }
    }

    private String json(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    public record AssignCommand(UUID positionId, EmploymentAssignment.AssignmentType assignmentType,
                                LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record AddReportingLineCommand(UUID subordinateEmploymentId, UUID managerEmploymentId,
                                          ReportingLine.LineType lineType, LocalDate effectiveFrom,
                                          LocalDate effectiveTo) {
    }

    public record CreateDelegationCommand(UUID delegatorEmploymentId, UUID delegateEmploymentId,
                                          UUID scopeOrgUnitId, String reason, LocalDate effectiveFrom,
                                          LocalDate effectiveTo) {
    }

    public record AssignOwnerRoleCommand(UUID employmentId, OwnerRole.RoleType roleType,
                                         LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record ReadinessView(boolean ready, List<OrganizationReadiness.Blocker> blockers,
                                List<OwnerRole> currentRoles, int ownerCount, int trusteeCount) {
    }
}
