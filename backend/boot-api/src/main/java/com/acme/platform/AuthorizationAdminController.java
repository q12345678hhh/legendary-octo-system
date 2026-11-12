package com.acme.platform;

import com.acme.platform.authorization.AuthorizationAdminService;
import com.acme.platform.authorization.AuthorizationGuard;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授权治理接口：角色、授权、显式拒绝、字段与条件策略。
 *
 * <p>全部要求对应的 {@code authorization.*} 权限；写操作额外要求 MFA。
 * 系统角色只能查看与复制，不能被租户直接修改。
 */
@RestController
@RequestMapping("/api/v1/authorization")
public class AuthorizationAdminController {

    public record CreateRoleRequest(String code, String name, String description,
                                    Set<String> permissions) {
    }

    public record PermissionsRequest(Set<String> permissions) {
    }

    public record GrantRequest(UUID actorId, UUID memberId, String roleCode, String scopeType,
                               UUID scopeId, OffsetDateTime validFrom, OffsetDateTime validTo,
                               String reason, UUID delegatedFromId, UUID requestId) {
    }

    public record AccessRequestCreateRequest(UUID requestedForActorId, String roleCode,
                                             String scopeType, UUID scopeId,
                                             OffsetDateTime validTo, String reason) {
    }

    public record DecisionRequest(String note) {
    }

    public record AccessRequestView(UUID id, UUID requesterActorId, UUID requestedForActorId,
                                    String roleCode, String scopeType, UUID scopeId,
                                    OffsetDateTime validTo, String reason, String state,
                                    UUID decidedByActorId, OffsetDateTime decidedAt,
                                    String decisionNote, UUID assignmentId,
                                    OffsetDateTime createdAt) {
    }

    public record ReviewView(UUID id, java.time.LocalDate periodStart,
                             java.time.LocalDate periodEnd, String state,
                             OffsetDateTime dueAt, OffsetDateTime createdAt,
                             OffsetDateTime closedAt) {
    }

    public record ReviewItemView(UUID id, UUID assignmentId, UUID subjectActorId,
                                 UUID reviewerActorId, String state,
                                 OffsetDateTime decidedAt, String note) {
    }

    public record ReviewDetailView(ReviewView review, List<ReviewItemView> items) {
    }

    public record AdminActionView(UUID id, UUID actorId, String action, String targetType,
                                  UUID targetId, String detail, OffsetDateTime occurredAt) {
    }

    public record DenyRequest(UUID actorId, String roleCode, String permissionCode,
                              String resourceType, UUID resourceId, String scopeType,
                              UUID scopeId, String reason, OffsetDateTime expiresAt) {
    }

    public record FieldPolicyRequest(String roleCode, String resourceType, String fieldName,
                                     boolean canView, boolean canEdit, boolean canExport,
                                     boolean canUseAi, String effect) {
    }

    public record ConditionPolicyRequest(String permissionCode, String name, String condition,
                                         String effect, int priority) {
    }

    public record AssignmentView(UUID id, UUID actorId, UUID memberId, String roleCode,
                                 String scopeType, UUID scopeId, OffsetDateTime validFrom,
                                 OffsetDateTime validTo, UUID delegatedFromId,
                                 OffsetDateTime revokedAt) {
    }

    public record DenyView(UUID id, UUID actorId, String roleCode, String permissionCode,
                           String resourceType, UUID resourceId, String scopeType, UUID scopeId,
                           OffsetDateTime expiresAt) {
    }

    private final AuthorizationGuard guard;
    private final AuthorizationAdminService service;
    private final com.acme.platform.authorization.AccessRequestService accessRequests;
    private final com.acme.platform.authorization.AccessReviewService accessReviews;

    public AuthorizationAdminController(AuthorizationGuard guard,
                                        AuthorizationAdminService service,
                                        com.acme.platform.authorization.AccessRequestService
                                                accessRequests,
                                        com.acme.platform.authorization.AccessReviewService
                                                accessReviews) {
        this.guard = guard;
        this.service = service;
        this.accessRequests = accessRequests;
        this.accessReviews = accessReviews;
    }

    @GetMapping("/roles")
    public List<AuthorizationAdminService.RoleView> roles() {
        guard.require("authorization.role.manage", "authorization.role", null, Map.of());
        return service.listRoles();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthorizationAdminService.RoleView createRole(@RequestBody CreateRoleRequest request) {
        guard.require("authorization.role.manage", "authorization.role", null, Map.of());
        guard.requireMfa();
        return service.createRole(new AuthorizationAdminService.CreateRoleCommand(
                request.code(), request.name(), request.description(), request.permissions()));
    }

    @PutMapping("/roles/{roleId}/permissions")
    public AuthorizationAdminService.RoleView replacePermissions(
            @PathVariable UUID roleId, @RequestBody PermissionsRequest request) {
        guard.require("authorization.role.manage", "authorization.role", roleId,
                Map.of("roleId", roleId));
        guard.requireMfa();
        return service.replacePermissions(roleId, request.permissions());
    }

    @GetMapping("/assignments")
    public List<AssignmentView> assignments(@RequestParam(required = false) UUID actorId,
                                            @RequestParam(defaultValue = "100") int limit) {
        guard.require("authorization.assignment.manage", "authorization.assignment", null,
                Map.of());
        return service.listAssignments(actorId, Math.max(1, Math.min(limit, 500))).stream()
                .map(row -> new AssignmentView(row.id(), row.actorId(), row.memberId(),
                        row.roleCode(), row.scopeType().wire(), row.scopeId(), row.validFrom(),
                        row.validTo(), row.delegatedFromId(), row.revokedAt()))
                .toList();
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView grant(@RequestBody GrantRequest request) {
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        if (request.actorId() != null) {
            attributes.put("targetActorId", request.actorId());
        }
        attributes.put("highRisk",
                com.acme.platform.authorization.SystemRoleCatalog.highRisk(request.roleCode()));
        guard.require("authorization.assignment.manage", "authorization.assignment", null,
                attributes);
        guard.requireMfa();
        var row = service.grant(new AuthorizationAdminService.GrantCommand(request.actorId(),
                request.memberId(), request.roleCode(), request.scopeType(), request.scopeId(),
                request.validFrom(), request.validTo(), request.reason(),
                request.delegatedFromId(), request.requestId()));
        return new AssignmentView(row.id(), row.actorId(), row.memberId(), row.roleCode(),
                row.scopeType().wire(), row.scopeId(), row.validFrom(), row.validTo(),
                row.delegatedFromId(), row.revokedAt());
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID assignmentId) {
        guard.require("authorization.assignment.manage", "authorization.assignment", assignmentId,
                Map.of("assignmentId", assignmentId));
        guard.requireMfa();
        service.revokeAssignment(assignmentId);
    }

    @GetMapping("/denies")
    public List<DenyView> denies(@RequestParam(defaultValue = "100") int limit) {
        guard.require("authorization.deny.manage", "authorization.deny", null, Map.of());
        return service.listDenies(Math.max(1, Math.min(limit, 500))).stream()
                .map(row -> new DenyView(row.id(), row.actorId(), row.roleCode(),
                        row.permissionCode(), row.resourceType(), row.resourceId(),
                        row.scopeType() == null ? null : row.scopeType().wire(), row.scopeId(),
                        row.expiresAt()))
                .toList();
    }

    @PostMapping("/denies")
    @ResponseStatus(HttpStatus.CREATED)
    public DenyView addDeny(@RequestBody DenyRequest request) {
        guard.require("authorization.deny.manage", "authorization.deny", null, Map.of());
        guard.requireMfa();
        var row = service.addDeny(new AuthorizationAdminService.DenyCommand(request.actorId(),
                request.roleCode(), request.permissionCode(), request.resourceType(),
                request.resourceId(), request.scopeType(), request.scopeId(), request.reason(),
                request.expiresAt()));
        return new DenyView(row.id(), row.actorId(), row.roleCode(), row.permissionCode(),
                row.resourceType(), row.resourceId(),
                row.scopeType() == null ? null : row.scopeType().wire(), row.scopeId(),
                row.expiresAt());
    }

    @DeleteMapping("/denies/{denyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDeny(@PathVariable UUID denyId) {
        guard.require("authorization.deny.manage", "authorization.deny", denyId,
                Map.of("denyId", denyId));
        guard.requireMfa();
        service.removeDeny(denyId);
    }

    @GetMapping("/field-policies")
    public List<FieldPolicyView> fieldPolicies() {
        guard.require("authorization.policy.manage", "authorization.policy", null, Map.of());
        return service.listFieldPolicies().stream()
                .map(row -> new FieldPolicyView(row.roleCode(), row.fieldName(),
                        row.canView(), row.canEdit(), row.canExport(), row.canUseAi(),
                        row.effect()))
                .toList();
    }

    @PutMapping("/field-policies")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putFieldPolicy(@RequestBody FieldPolicyRequest request) {
        guard.require("authorization.policy.manage", "authorization.policy", null, Map.of());
        guard.requireMfa();
        service.putFieldPolicy(new AuthorizationAdminService.FieldPolicyCommand(
                request.roleCode(), request.resourceType(), request.fieldName(), request.canView(),
                request.canEdit(), request.canExport(), request.canUseAi(), request.effect()));
    }

    @GetMapping("/condition-policies")
    public List<ConditionPolicyView> conditionPolicies() {
        guard.require("authorization.policy.manage", "authorization.policy", null, Map.of());
        return service.listConditionPolicies().stream()
                .map(row -> new ConditionPolicyView(row.permissionCode(), row.name(),
                        row.condition(), row.effect(), row.priority()))
                .toList();
    }

    @PutMapping("/condition-policies")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putConditionPolicy(@RequestBody ConditionPolicyRequest request) {
        guard.require("authorization.policy.manage", "authorization.policy", null, Map.of());
        guard.requireMfa();
        service.putConditionPolicy(new AuthorizationAdminService.ConditionCommand(
                request.permissionCode(), request.name(), request.condition(), request.effect(),
                request.priority()));
    }

    // ---------------------------------------------------------------- 权限申请

    @PostMapping("/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessRequestView createAccessRequest(
            @RequestBody AccessRequestCreateRequest request) {
        guard.require("authorization.access.request", "authorization.access_request", null,
                Map.of());
        return accessRequestView(accessRequests.create(
                new com.acme.platform.authorization.AccessRequestService.CreateCommand(
                        request.requestedForActorId(), request.roleCode(), request.scopeType(),
                        request.scopeId(), request.validTo(), request.reason())));
    }

    @GetMapping("/access-requests")
    public List<AccessRequestView> accessRequests(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "100") int limit) {
        return accessRequests.list(state, Math.max(1, Math.min(limit, 200))).stream()
                .map(AuthorizationAdminController::accessRequestView)
                .toList();
    }

    @PostMapping("/access-requests/{requestId}/approve")
    public AccessRequestView approveAccessRequest(@PathVariable UUID requestId,
                                                  @RequestBody(required = false)
                                                  DecisionRequest request) {
        guard.requireMfa();
        return accessRequestView(accessRequests.approve(requestId,
                request == null ? null : request.note()));
    }

    @PostMapping("/access-requests/{requestId}/reject")
    public AccessRequestView rejectAccessRequest(@PathVariable UUID requestId,
                                                 @RequestBody(required = false)
                                                 DecisionRequest request) {
        guard.requireMfa();
        return accessRequestView(accessRequests.reject(requestId,
                request == null ? null : request.note()));
    }

    @PostMapping("/access-requests/{requestId}/cancel")
    public AccessRequestView cancelAccessRequest(@PathVariable UUID requestId) {
        guard.require("authorization.access.request", "authorization.access_request", null,
                Map.of());
        return accessRequestView(accessRequests.cancel(requestId));
    }

    // ---------------------------------------------------------------- 季度复核

    @GetMapping("/access-reviews")
    public List<ReviewView> accessReviews(@RequestParam(required = false) String state,
                                          @RequestParam(defaultValue = "50") int limit) {
        return accessReviews.list(state, Math.max(1, Math.min(limit, 100))).stream()
                .map(AuthorizationAdminController::reviewView)
                .toList();
    }

    @GetMapping("/access-reviews/{reviewId}")
    public ReviewDetailView accessReview(@PathVariable UUID reviewId) {
        var detail = accessReviews.detail(reviewId);
        return new ReviewDetailView(reviewView(detail.review()),
                detail.items().stream().map(AuthorizationAdminController::reviewItemView)
                        .toList());
    }

    @PostMapping("/access-reviews/{reviewId}/items/{itemId}/confirm")
    public ReviewItemView confirmReviewItem(@PathVariable UUID reviewId,
                                            @PathVariable UUID itemId,
                                            @RequestBody(required = false)
                                            DecisionRequest request) {
        return reviewItemView(accessReviews.confirm(reviewId, itemId,
                request == null ? null : request.note()));
    }

    @PostMapping("/access-reviews/{reviewId}/items/{itemId}/revoke")
    public ReviewItemView revokeReviewItem(@PathVariable UUID reviewId,
                                           @PathVariable UUID itemId,
                                           @RequestBody(required = false)
                                           DecisionRequest request) {
        guard.requireMfa();
        return reviewItemView(accessReviews.revoke(reviewId, itemId,
                request == null ? null : request.note()));
    }

    @PostMapping("/access-reviews/{reviewId}/close")
    public ReviewView closeAccessReview(@PathVariable UUID reviewId) {
        guard.requireMfa();
        return reviewView(accessReviews.close(reviewId));
    }

    @PostMapping("/access-reviews/generate")
    public ReviewDetailView generateAccessReview() {
        guard.requireMfa();
        var detail = accessReviews.generateCurrentTenant();
        return new ReviewDetailView(reviewView(detail.review()),
                detail.items().stream().map(AuthorizationAdminController::reviewItemView)
                        .toList());
    }

    @GetMapping("/admin-actions")
    public List<AdminActionView> adminActions(@RequestParam(defaultValue = "100") int limit) {
        guard.require("authorization.admin_action.read", "authorization.admin_action", null,
                Map.of());
        return service.listAdminActions(Math.max(1, Math.min(limit, 200))).stream()
                .map(row -> new AdminActionView(row.id(), row.actorId(), row.action(),
                        row.targetType(), row.targetId(), row.detail(), row.occurredAt()))
                .toList();
    }

    private static AccessRequestView accessRequestView(
            com.acme.platform.authorization.AccessRequestStore.AccessRequestRow row) {
        return new AccessRequestView(row.id(), row.requesterActorId(),
                row.requestedForActorId(), row.roleCode(), row.scopeType().wire(), row.scopeId(),
                row.validTo(), row.reason(), row.state(), row.decidedByActorId(),
                row.decidedAt(), row.decisionNote(), row.assignmentId(), row.createdAt());
    }

    private static ReviewView reviewView(
            com.acme.platform.authorization.AccessReviewStore.ReviewRow row) {
        return new ReviewView(row.id(), row.periodStart(), row.periodEnd(), row.state(),
                row.dueAt(), row.createdAt(), row.closedAt());
    }

    private static ReviewItemView reviewItemView(
            com.acme.platform.authorization.AccessReviewStore.ItemRow row) {
        return new ReviewItemView(row.id(), row.assignmentId(), row.subjectActorId(),
                row.reviewerActorId(), row.state(), row.decidedAt(), row.note());
    }

    public record FieldPolicyView(String roleCode, String fieldName, boolean canView,
                                  boolean canEdit, boolean canExport, boolean canUseAi,
                                  String effect) {
    }

    public record ConditionPolicyView(String permissionCode, String name, String condition,
                                      String effect, int priority) {
    }
}
