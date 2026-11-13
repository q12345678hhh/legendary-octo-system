package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AccessScope;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.department.DepartmentWorkspaceService;
import com.acme.platform.department.insight.InsightService;
import com.acme.platform.department.insight.InsightStore;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.worklog.access.WorkAccessLogService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理洞察：固定维度统计 + 阈值抑制 + 二次鉴权下钻。
 *
 * <p>所有端点在统一授权守卫上取结论：部门范围走部门口径，人员范围（直属/汇报链）
 * 走人员口径。下钻返回的每一行都要再次通过 {@code worklog.time.read}，
 * 二次过滤后人数低于阈值时整表抑制，避免用差分查询反推个人。
 */
@RestController
@RequestMapping("/api/v1/insights")
class InsightController {

    private static final String READ_PERMISSION = "insight.work.read";
    private static final String RESOURCE_TYPE = "insight.work";

    private final InsightService insights;
    private final DepartmentWorkspaceService departments;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;
    private final WorkAccessLogService accessLog;

    InsightController(InsightService insights,
                      DepartmentWorkspaceService departments,
                      AuthorizationGuard authorization,
                      MemberStore memberStore,
                      WorkAccessLogService accessLog) {
        this.insights = insights;
        this.departments = departments;
        this.authorization = authorization;
        this.memberStore = memberStore;
        this.accessLog = accessLog;
    }

    record DepartmentOption(UUID orgUnitId, String name) {
    }

    record SettingView(int minGroupSize, int maxDailyMinutes) {
    }

    record UpdateSettingRequest(Integer minGroupSize) {
    }

    record DrilldownRow(UUID personId, String displayName, int submittedDays, long totalMinutes) {
    }

    record DrilldownResponse(int threshold, List<DrilldownRow> rows) {
    }

    /** 可选择的范围：租户级返回全部空间，部门范围返回授权空间，人员范围返回空集合。 */
    @GetMapping("/departments")
    List<DepartmentOption> departments() {
        AccessDecision decision = requireRead(null);
        AccessScope scope = decision.scope();
        return departments.spaces(scope.tenantWide(), scope.orgUnitIds()).stream()
                .map(space -> new DepartmentOption(space.orgUnitId(), space.name()))
                .toList();
    }

    @GetMapping("/work")
    InsightService.Overview work(
            @RequestParam(required = false) UUID orgUnitId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return resolveOverview(orgUnitId, from, to);
    }

    @GetMapping("/work/people")
    DrilldownResponse people(
            @RequestParam(required = false) UUID orgUnitId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<InsightStore.PersonRow> rows = resolveDrilldown(orgUnitId, from, to);
        int threshold = insights.threshold();
        UUID viewer = viewerPersonId();

        List<DrilldownRow> visible = new ArrayList<>();
        for (InsightStore.PersonRow row : rows) {
            boolean allowed = authorization.decide("worklog.time.read", "worklog.time",
                    row.personId(), Map.of("personId", row.personId(),
                            "ownerId", row.personId())).allowed();
            if (!allowed) {
                continue;
            }
            visible.add(new DrilldownRow(row.personId(), row.displayName(),
                    row.submittedDays(), row.totalMinutes()));
            accessLog.record(viewer, row.personId(), "insight", null, "insight_drilldown",
                    List.of(READ_PERMISSION, "worklog.time.read"));
        }
        if (visible.size() < threshold) {
            // 二次鉴权后剩余人群不足阈值：整表抑制，不暴露「有多少行被过滤」
            return new DrilldownResponse(threshold, List.of());
        }
        return new DrilldownResponse(threshold, visible);
    }

    @GetMapping("/settings")
    SettingView settings() {
        requireRead(null);
        InsightStore.Setting setting = insights.settings();
        return new SettingView(setting.minGroupSize(), setting.maxDailyMinutes());
    }

    @PutMapping("/settings")
    SettingView updateSettings(@RequestBody UpdateSettingRequest request) {
        authorization.require("tenant.admin", "tenant", null, Map.of());
        ActorContext actor = authorization.actor();
        String updatedBy = actor == null || actor.actorId() == null
                ? null : actor.actorId().toString();
        InsightStore.Setting setting = insights.updateMinGroupSize(request.minGroupSize(),
                updatedBy);
        return new SettingView(setting.minGroupSize(), setting.maxDailyMinutes());
    }

    private InsightService.Overview resolveOverview(UUID requestedUnit, LocalDate from,
                                                    LocalDate to) {
        AccessScope scope = requireRead(requestedUnit).scope();
        UUID unit = pickUnit(requestedUnit, scope);
        if (unit != null) {
            return insights.overview(unit, from, to);
        }
        if (!scope.personIds().isEmpty()) {
            return insights.overviewForPeople(null, scope.personIds(), from, to);
        }
        throw ApiException.badRequest("INSIGHT_ORG_UNIT_REQUIRED", "请选择要统计的部门");
    }

    private List<InsightStore.PersonRow> resolveDrilldown(UUID requestedUnit, LocalDate from,
                                                          LocalDate to) {
        AccessScope scope = requireRead(requestedUnit).scope();
        UUID unit = pickUnit(requestedUnit, scope);
        if (unit != null) {
            return insights.drilldown(unit, from, to);
        }
        if (!scope.personIds().isEmpty()) {
            return insights.drilldownForPeople(null, scope.personIds(), from, to);
        }
        throw ApiException.badRequest("INSIGHT_ORG_UNIT_REQUIRED", "请选择要统计的部门");
    }

    /**
     * 解析本次统计使用的部门：显式请求且在范围内时用它；
     * 否则在「非租户级且只有一个部门」时自动落到该部门。
     */
    private UUID pickUnit(UUID requestedUnit, AccessScope scope) {
        if (requestedUnit != null && (scope.tenantWide() || scope.includesOrgUnit(requestedUnit))) {
            return requestedUnit;
        }
        if (!scope.tenantWide() && scope.orgUnitIds().size() == 1) {
            return scope.orgUnitIds().iterator().next();
        }
        return null;
    }

    private AccessDecision requireRead(UUID orgUnitId) {
        Map<String, Object> attributes = orgUnitId == null
                ? Map.of() : Map.of("orgUnitId", orgUnitId);
        return authorization.require(READ_PERMISSION, RESOURCE_TYPE, orgUnitId, attributes);
    }

    private UUID viewerPersonId() {
        ActorContext actor = authorization.actor();
        if (actor == null || actor.memberId() == null) {
            return null;
        }
        return memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .orElse(null);
    }
}
