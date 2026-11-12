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
                               String reason, UUID delegatedFromId) {
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

    public AuthorizationAdminController(AuthorizationGuard guard,
                                        AuthorizationAdminService service) {
        this.guard = guard;
        this.service = service;
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
        guard.require("authorization.assignment.manage", "authorization.assignment", null,
                Map.of());
        guard.requireMfa();
        var row = service.grant(new AuthorizationAdminService.GrantCommand(request.actorId(),
                request.memberId(), request.roleCode(), request.scopeType(), request.scopeId(),
                request.validFrom(), request.validTo(), request.reason(),
                request.delegatedFromId()));
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

    public record FieldPolicyView(String roleCode, String fieldName, boolean canView,
                                  boolean canEdit, boolean canExport, boolean canUseAi,
                                  String effect) {
    }

    public record ConditionPolicyView(String permissionCode, String name, String condition,
                                      String effect, int priority) {
    }
}
