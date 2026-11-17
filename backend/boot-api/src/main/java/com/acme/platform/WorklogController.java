package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.identity.MemberStore;
import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.EmploymentScope;
import com.acme.platform.organization.OrganizationScopeQueries;
import com.acme.platform.platform.ActorContext;
import com.acme.platform.platform.ApiException;
import com.acme.platform.worklog.report.WorklogReportService;
import com.acme.platform.worklog.time.WorklogEntry;
import com.acme.platform.worklog.time.WorklogEntryService;
import com.acme.platform.worklog.time.WorklogSubmissionService;
import com.acme.platform.workflow.WorkflowInstanceService;
import com.acme.platform.workflow.WorkflowTaskService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工工时与周报。
 *
 * <p>写操作只作用于当前主体本人：personId 永远从会话解析，忽略请求体中的任何人标识。
 * 主管/管理员/AI/服务账号都无法通过该端点代交或改他人工时。
 */
@RestController
@RequestMapping("/api/v1/worklog")
class WorklogController {

    private final WorklogEntryService entries;
    private final WorklogSubmissionService submissions;
    private final WorklogReportService reports;
    private final AuthorizationGuard authorization;
    private final MemberStore memberStore;
    private final OrganizationScopeQueries organizationScopes;
    private final WorkflowTaskService workflowTasks;
    private final WorkflowInstanceService workflowInstances;

    WorklogController(WorklogEntryService entries,
                      WorklogSubmissionService submissions,
                      WorklogReportService reports,
                      AuthorizationGuard authorization,
                      MemberStore memberStore,
                      OrganizationScopeQueries organizationScopes,
                      WorkflowTaskService workflowTasks,
                      WorkflowInstanceService workflowInstances) {
        this.entries = entries;
        this.submissions = submissions;
        this.reports = reports;
        this.authorization = authorization;
        this.memberStore = memberStore;
        this.organizationScopes = organizationScopes;
        this.workflowTasks = workflowTasks;
        this.workflowInstances = workflowInstances;
    }

    record EntryRequest(LocalDate workDate,
                        OffsetDateTime startsAt,
                        OffsetDateTime endsAt,
                        Integer durationMinutes,
                        UUID projectId,
                        UUID taskId,
                        String description,
                        String clientEntryId) {
    }

    record MergeRequest(List<UUID> entryIds, String description) {
    }

    record SplitRequest(int minutes) {
    }

    record ReviewRequest(String action, String comment) {
    }

    record NotesRequest(String issues, String nextWeekPlan) {
    }

    record ReportEnvelope(WorklogReportService.ReportView report) {
    }

    record EntryView(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, int minutes,
                     UUID projectId, UUID taskId, String description, String clientEntryId,
                     int draftVersion) {
    }

    record DayView(LocalDate workDate, String state,
                   List<EntryView> entries, WorklogSubmissionService.SubmissionView submission) {
    }

    @GetMapping("/days/{date}")
    DayView day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID personId = requireSelf("worklog.time.read");
        WorklogSubmissionService.SubmissionView submission = submissions.current(personId, date);
        List<EntryView> rows = entries.list(personId, date).stream()
                .map(WorklogController::toView)
                .toList();
        String state = submission == null ? "draft" : submission.status();
        return new DayView(date, state, rows, submission);
    }

    @PostMapping("/entries")
    EntryView create(@RequestBody EntryRequest request) {
        UUID personId = requireSelf("worklog.time.write");
        WorklogEntry entry = entries.create(personId, employmentId(personId), toInput(request));
        return toView(entry);
    }

    @PatchMapping("/entries/{entryId}")
    EntryView update(@PathVariable UUID entryId, @RequestBody EntryRequest request) {
        UUID personId = requireSelf("worklog.time.write");
        return toView(entries.update(personId, entryId, toInput(request)));
    }

    @DeleteMapping("/entries/{entryId}")
    void delete(@PathVariable UUID entryId) {
        UUID personId = requireSelf("worklog.time.write");
        entries.delete(personId, entryId);
    }

    @PostMapping("/entries/merge")
    EntryView merge(@RequestBody MergeRequest request) {
        UUID personId = requireSelf("worklog.time.write");
        return toView(entries.merge(personId, request.entryIds(), request.description()));
    }

    @PostMapping("/entries/{entryId}/split")
    List<EntryView> split(@PathVariable UUID entryId, @RequestBody SplitRequest request) {
        UUID personId = requireSelf("worklog.time.write");
        return entries.split(personId, entryId, request.minutes()).stream()
                .map(WorklogController::toView)
                .toList();
    }

    @PostMapping("/days/{date}/submit")
    WorklogSubmissionService.SubmissionView submit(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID personId = requireSelf("worklog.time.write");
        WorklogSubmissionService.SubmissionView view = submissions.submit(personId, date, personId);
        orchestrateHours(personId, view);
        return view;
    }

    @GetMapping("/submissions/{submissionId}")
    WorklogSubmissionService.SubmissionView submission(@PathVariable UUID submissionId) {
        WorklogSubmissionService.SubmissionView view = submissions.find(submissionId);
        authorization.require("worklog.time.read", "worklog.time", submissionId,
                Map.of("personId", view.personId(), "ownerId", view.personId()));
        return view;
    }

    @PostMapping("/submissions/{submissionId}/review")
    WorklogSubmissionService.SubmissionView reviewSubmission(
            @PathVariable UUID submissionId, @RequestBody ReviewRequest request) {
        UUID reviewer = viewerPersonIdOrThrow();
        WorklogSubmissionService.SubmissionView view = submissions.find(submissionId);
        authorization.require("worklog.time.review", "worklog.time", submissionId,
                Map.of("personId", view.personId()));
        WorklogSubmissionService.SubmissionView reviewed = submissions.review(reviewer,
                submissionId, request.action(), request.comment());
        workflowTasks.markDecided("worklog.time", submissionId, reviewer, request.action(),
                request.comment());
        return reviewed;
    }

    @GetMapping("/reviews")
    List<WorklogSubmissionService.SubmissionView> pendingReviews() {
        AccessDecision decision = authorization.require("worklog.time.review",
                "worklog.time", null, Map.of());
        return submissions.pending(decision.scope().personIds());
    }

    @GetMapping("/reports/{weekStart}")
    ReportEnvelope report(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        UUID personId = requireSelf("worklog.report.read");
        return new ReportEnvelope(reports.latest(personId, weekStart));
    }

    @PostMapping("/reports/{weekStart}/generate")
    WorklogReportService.ReportView generate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        UUID personId = requireSelf("worklog.report.submit");
        return reports.generate(personId, weekStart);
    }

    @PatchMapping("/reports/{weekStart}")
    WorklogReportService.ReportView notes(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            @RequestBody NotesRequest request) {
        UUID personId = requireSelf("worklog.report.submit");
        return reports.updateNotes(personId, weekStart, request.issues(), request.nextWeekPlan());
    }

    @PostMapping("/reports/{weekStart}/submit")
    WorklogReportService.ReportView submitReport(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        UUID personId = requireSelf("worklog.report.submit");
        WorklogReportService.ReportView view = reports.submit(personId, weekStart);
        orchestrateReport(personId, view);
        return view;
    }

    @PostMapping("/reports/{reportId}/review")
    WorklogReportService.ReportView reviewReport(@PathVariable UUID reportId,
                                                 @RequestBody ReviewRequest request) {
        UUID reviewer = viewerPersonIdOrThrow();
        WorklogReportService.ReportView view = reports.find(reportId);
        authorization.require("worklog.report.review", "worklog.report", reportId,
                Map.of("personId", view.personId()));
        WorklogReportService.ReportView reviewed = reports.review(reviewer, reportId,
                request.action(), request.comment());
        workflowTasks.markDecided("worklog.report", reportId, reviewer, request.action(),
                request.comment());
        return reviewed;
    }

    @GetMapping("/reports/reviews")
    List<WorklogReportService.ReportView> pendingReports() {
        AccessDecision decision = authorization.require("worklog.report.review",
                "worklog.report", null, Map.of());
        return reports.pending(decision.scope().personIds());
    }

    /**
     * 提交成功后的工作流编排：默认生成直连审核任务；租户发布定义后改走实例。
     *
     * <p>编排失败不回滚已提交的工时；重复提交会复用同一快照并幂等补建任务。
     */
    private void orchestrateHours(UUID personId, WorklogSubmissionService.SubmissionView view) {
        if (view == null || view.id() == null) {
            return;
        }
        java.util.Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put("personId", personId.toString());
        variables.put("workDate", String.valueOf(view.workDate()));
        variables.put("totalMinutes", view.totalMinutes());
        orchestrate("worklog.day.review", "worklog.time", view.id(), "工时待审核", personId,
                variables);
    }

    private void orchestrateReport(UUID personId, WorklogReportService.ReportView view) {
        if (view == null || view.id() == null) {
            return;
        }
        java.util.Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put("personId", personId.toString());
        variables.put("weekStart", String.valueOf(view.weekStart()));
        orchestrate("worklog.report.review", "worklog.report", view.id(), "周报待审核", personId,
                variables);
    }

    private void orchestrate(String definitionKey, String businessType, UUID businessId,
                             String title, UUID personId,
                             java.util.Map<String, Object> variables) {
        if (workflowInstances.hasPublished(definitionKey)) {
            workflowInstances.start(definitionKey, businessType, businessId,
                    businessId.toString(), variables, personId);
            return;
        }
        UUID manager = organizationScopes.managerPersonOf(personId, LocalDate.now()).orElse(null);
        if (manager != null) {
            workflowTasks.openDirectTask(businessType, businessId, "review", title, "direct",
                    manager, null);
        }
    }

    private UUID requireSelf(String permission) {
        UUID personId = viewerPersonIdOrThrow();
        authorization.require(permission, permission.startsWith("worklog.report")
                ? "worklog.report" : "worklog.time", personId,
                Map.of("personId", personId, "ownerId", personId));
        return personId;
    }

    private UUID viewerPersonIdOrThrow() {
        return viewerPersonId().orElseThrow(() ->
                ApiException.forbidden("PERSON_NOT_BOUND", "当前账号未绑定员工"));
    }

    private UUID employmentId(UUID personId) {
        return organizationScopes.scopeOfPerson(personId, LocalDate.now())
                .map(EmploymentScope::employmentId)
                .orElse(null);
    }

    private java.util.Optional<UUID> viewerPersonId() {
        ActorContext actor = authorization.actor();
        if (actor == null || actor.memberId() == null) {
            return java.util.Optional.empty();
        }
        return memberStore.findById(actor.memberId())
                .map(TenantMember::personId)
                .filter(java.util.Objects::nonNull);
    }

    private static WorklogEntryService.EntryInput toInput(EntryRequest request) {
        return new WorklogEntryService.EntryInput(request.workDate(), request.startsAt(),
                request.endsAt(), request.durationMinutes(), request.projectId(),
                request.taskId(), request.description(), request.clientEntryId());
    }

    private static EntryView toView(WorklogEntry entry) {
        return new EntryView(entry.id(), entry.startsAt(), entry.endsAt(),
                entry.durationMinutes(), entry.projectId(), entry.taskId(),
                entry.description(), entry.clientEntryId(), entry.draftVersion());
    }
}
