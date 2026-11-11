package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.organization.Delegation;
import com.acme.platform.organization.EmploymentAssignment;
import com.acme.platform.organization.OrganizationRelationService;
import com.acme.platform.organization.ReportingLine;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主/兼岗、实线/虚线汇报与限时代管。
 *
 * <p>所有关系都带生效区间：查询可以看历史，也可以只取当前生效；
 * 汇报道成环、区间重叠、代管超期在写入时就直接拒绝。
 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationAssignmentController {

    private final OrganizationRelationService relationService;
    private final IdentityGuard guard;

    public OrganizationAssignmentController(OrganizationRelationService relationService,
                                            IdentityGuard guard) {
        this.relationService = relationService;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- 主/兼岗

    @GetMapping("/employments/{employmentId}/assignments")
    public List<EmploymentAssignment> assignments(@PathVariable UUID employmentId,
                                                  @RequestParam(defaultValue = "false") boolean current) {
        guard.requireUser();
        return current ? relationService.currentAssignments(employmentId)
                : relationService.assignments(employmentId);
    }

    @PostMapping("/employments/{employmentId}/assignments")
    public EmploymentAssignment assign(@PathVariable UUID employmentId,
                                       @RequestBody AssignRequest request) {
        guard.requireAdmin(false);
        return relationService.assign(employmentId,
                new OrganizationRelationService.AssignCommand(request.positionId(),
                        EmploymentAssignment.AssignmentType.fromWire(request.assignmentType()),
                        request.effectiveFrom(), request.effectiveTo()));
    }

    @PostMapping("/assignments/{assignmentId}/end")
    public EmploymentAssignment endAssignment(@PathVariable UUID assignmentId,
                                              @RequestBody EndRequest request) {
        guard.requireAdmin(false);
        return relationService.endAssignment(assignmentId, request.effectiveTo());
    }

    // ---------------------------------------------------------------- 汇报线

    @GetMapping("/reporting-lines")
    public List<ReportingLine> reportingLines() {
        guard.requireUser();
        return relationService.reportingLines();
    }

    @GetMapping("/employments/{employmentId}/reporting-lines")
    public List<ReportingLine> reportingLinesOf(@PathVariable UUID employmentId) {
        guard.requireUser();
        return relationService.reportingLinesOf(employmentId);
    }

    @PostMapping("/reporting-lines")
    public ReportingLine addReportingLine(@RequestBody ReportingLineRequest request) {
        guard.requireAdmin(false);
        return relationService.addReportingLine(new OrganizationRelationService.AddReportingLineCommand(
                request.subordinateEmploymentId(), request.managerEmploymentId(),
                ReportingLine.LineType.fromWire(request.lineType()),
                request.effectiveFrom(), request.effectiveTo()));
    }

    @PostMapping("/reporting-lines/{lineId}/end")
    public ReportingLine endReportingLine(@PathVariable UUID lineId,
                                          @RequestBody EndRequest request) {
        guard.requireAdmin(false);
        return relationService.endReportingLine(lineId, request.effectiveTo());
    }

    // ---------------------------------------------------------------- 代管

    @GetMapping("/delegations")
    public List<Delegation> delegations(@RequestParam(defaultValue = "false") boolean current) {
        guard.requireUser();
        return current ? relationService.currentDelegations() : relationService.delegations();
    }

    @PostMapping("/delegations")
    public Delegation createDelegation(@RequestBody DelegationRequest request) {
        guard.requireAdmin(false);
        return relationService.createDelegation(new OrganizationRelationService.CreateDelegationCommand(
                request.delegatorEmploymentId(), request.delegateEmploymentId(),
                request.scopeOrgUnitId(), request.reason(), request.effectiveFrom(),
                request.effectiveTo()));
    }

    @PostMapping("/delegations/{delegationId}/end")
    public Delegation endDelegation(@PathVariable UUID delegationId,
                                    @RequestBody EndRequest request) {
        guard.requireAdmin(false);
        return relationService.endDelegation(delegationId, request.effectiveTo());
    }

    public record AssignRequest(UUID positionId, String assignmentType, LocalDate effectiveFrom,
                                LocalDate effectiveTo) {
    }

    public record ReportingLineRequest(UUID subordinateEmploymentId, UUID managerEmploymentId,
                                       String lineType, LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    public record DelegationRequest(UUID delegatorEmploymentId, UUID delegateEmploymentId,
                                    UUID scopeOrgUnitId, String reason, LocalDate effectiveFrom,
                                    LocalDate effectiveTo) {
    }

    public record EndRequest(LocalDate effectiveTo) {
    }
}
