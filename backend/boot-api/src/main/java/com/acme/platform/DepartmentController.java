package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.department.DepartmentSpaceStore;
import com.acme.platform.department.DepartmentWorkspaceService;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.EmploymentScope;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.work.WorkStatusQuery;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门工作区：第一屏固定表格。
 *
 * <p>姓名、职位、汇报层级是公开字段；今日工时、周报状态、授权文件数逐格单独授权，
 * 无权时字段整体省略（返回 JSON 中不存在），不返回数量、摘要或可猜测链接。
 */
@RestController
@RequestMapping("/api/v1/departments")
class DepartmentController {

    private final DepartmentWorkspaceService service;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;
    private final OrganizationScopeQueries organizationScopes;
    private final DepartmentFileCountQuery fileCounts;
    private final ObjectProvider<WorkStatusQuery> workStatus;

    DepartmentController(DepartmentWorkspaceService service,
                         AuthorizationGuard authorization,
                         MemberStore memberStore,
                         OrganizationScopeQueries organizationScopes,
                         DepartmentFileCountQuery fileCounts,
                         ObjectProvider<WorkStatusQuery> workStatus) {
        this.service = service;
        this.authorization = authorization;
        this.memberStore = memberStore;
        this.organizationScopes = organizationScopes;
        this.fileCounts = fileCounts;
        this.workStatus = workStatus;
    }

    record DepartmentSummary(UUID orgUnitId, String name, String path) {
    }

    record UpdateSettingRequest(String dayEndTime, Integer weekReportDueWeekday) {
    }

    record SettingView(String dayEndTime, int weekReportDueWeekday) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MemberView(UUID personId,
                      String displayName,
                      String positionName,
                      String jobName,
                      String assignmentType,
                      String managerName,
                      int depth,
                      boolean workPageAccessible,
                      String workPagePath,
                      Integer todayMinutes,
                      String dayState,
                      String weekReportState,
                      Integer authorizedFileCount) {
    }

    record WorkspaceView(UUID orgUnitId,
                         String name,
                         String status,
                         String dayEndTime,
                         int weekReportDueWeekday,
                         List<MemberView> members) {
    }

    @GetMapping
    List<DepartmentSummary> list() {
        AccessDecision decision = authorization.require("department.workspace.read",
                "department.workspace", null, Map.of());
        Set<UUID> units = decision.scope().orgUnitIds();
        boolean tenantWide = decision.scope().tenantWide();
        if (!tenantWide && units.isEmpty()) {
            UUID primary = viewerScope().map(EmploymentScope::primaryOrgUnitId).orElse(null);
            units = primary == null ? Set.of() : Set.of(primary);
        }
        return service.spaces(tenantWide, units).stream()
                .map(space -> new DepartmentSummary(space.orgUnitId(), space.name(),
                        "#/departments/" + space.orgUnitId()))
                .toList();
    }

    @GetMapping("/{orgUnitId}/workspace")
    WorkspaceView workspace(@PathVariable UUID orgUnitId) {
        authorization.require("department.workspace.read", "department.workspace",
                orgUnitId, Map.of("orgUnitId", orgUnitId));
        DepartmentWorkspaceService.Workspace workspace = service.requireWorkspace(orgUnitId);

        List<UUID> personIds = workspace.members().stream()
                .map(DepartmentSpaceStore.MemberRow::personId)
                .distinct()
                .toList();
        WorkStatusQuery query = workStatus.getIfAvailable();
        Map<UUID, WorkStatusQuery.PersonWorkStatus> statuses = query == null
                ? Map.of() : query.statuses(personIds, LocalDate.now());
        Map<UUID, Integer> fileCountsByPerson = fileCounts.counts(personIds);

        List<MemberView> rows = workspace.members().stream()
                .map(member -> row(orgUnitId, member, statuses.get(member.personId()),
                        fileCountsByPerson.get(member.personId())))
                .toList();
        return new WorkspaceView(workspace.space().orgUnitId(), workspace.space().name(),
                workspace.space().status(),
                workspace.setting().dayEndTime().toString(),
                workspace.setting().weekReportDueWeekday(), rows);
    }

    @PutMapping("/{orgUnitId}/settings")
    SettingView updateSetting(@PathVariable UUID orgUnitId,
                              @RequestBody UpdateSettingRequest request) {
        authorization.require("department.workspace.manage", "department.workspace",
                orgUnitId, Map.of("orgUnitId", orgUnitId));
        LocalTime dayEnd = request.dayEndTime() == null ? null : LocalTime.parse(request.dayEndTime());
        int weekday = request.weekReportDueWeekday() == null ? 5 : request.weekReportDueWeekday();
        DepartmentSpaceStore.Setting setting = service.updateSetting(orgUnitId, dayEnd, weekday,
                authorization.actor().actorId() == null ? null
                        : authorization.actor().actorId().toString());
        return new SettingView(setting.dayEndTime().toString(), setting.weekReportDueWeekday());
    }

    private MemberView row(UUID orgUnitId, DepartmentSpaceStore.MemberRow member,
                           WorkStatusQuery.PersonWorkStatus status, Integer fileCount) {
        boolean workPage = allows("business.work.read", member.personId(), orgUnitId);
        boolean timeVisible = allows("worklog.time.read", member.personId(), orgUnitId);
        boolean reportVisible = allows("worklog.report.read", member.personId(), orgUnitId);
        Integer todayMinutes = timeVisible && status != null ? status.todayMinutes() : null;
        String dayState = timeVisible && status != null ? status.dayState() : null;
        String weekState = reportVisible && status != null ? status.weekReportState() : null;
        Integer authorizedFiles = workPage && allows("file.read", member.personId(), orgUnitId)
                ? fileCount : null;
        return new MemberView(member.personId(), member.displayName(), member.positionName(),
                member.jobName(), member.assignmentType(), member.managerName(), member.depth(),
                workPage, workPage ? "#/work/" + member.personId() : null,
                todayMinutes, dayState, weekState, authorizedFiles);
    }

    private boolean allows(String permission, UUID personId, UUID orgUnitId) {
        return authorization.decide(permission, permissionResourceType(permission), personId,
                Map.of("personId", personId, "ownerId", personId, "orgUnitId", orgUnitId)).allowed();
    }

    private String permissionResourceType(String permission) {
        return switch (permission) {
            case "business.work.read" -> "business.work";
            case "file.read" -> "file";
            default -> permission;
        };
    }

    private java.util.Optional<EmploymentScope> viewerScope() {
        ActorContext actor = authorization.actor();
        if (actor.memberId() == null) {
            return java.util.Optional.empty();
        }
        TenantMember member = memberStore.findById(actor.memberId()).orElse(null);
        if (member == null || member.personId() == null) {
            return java.util.Optional.empty();
        }
        return organizationScopes.scopeOfPerson(member.personId(), LocalDate.now());
    }
}
