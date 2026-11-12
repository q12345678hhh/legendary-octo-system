package com.acme.platform;

import com.acme.platform.authorization.AccessDecision;
import com.acme.platform.authorization.AuthorizationAdminService;
import com.acme.platform.authorization.AuthorizationGuard;
import com.acme.platform.authorization.AuthorizationService;
import com.acme.platform.authorization.PermissionCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限解释器与模拟器。
 *
 * <p>解释与模拟都需要 {@code authorization.decision.explain}；普通主体只能判定自己，
 * 判定他人需要 {@code authorization.decision.check}。解释结果只返回因素码，不回传策略原文。
 */
@RestController
@RequestMapping("/api/v1/authorization")
public class AuthorizationController {

    public record CheckRequest(UUID actorId, String permission, String resourceType,
                               UUID resourceId, Map<String, Object> attributes) {
    }

    public record SimulationRequest(List<AuthorizationAdminService.SimulatedGrant> grants,
                                    List<AuthorizationAdminService.SimulatedDeny> denies,
                                    List<AuthorizationAdminService.SimulationCheck> checks) {
    }

    public record DecisionLogView(UUID id, UUID actorId, String permissionCode,
                                  String resourceType, UUID resourceId, boolean allowed,
                                  String reasonCode, String factors,
                                  java.time.OffsetDateTime occurredAt) {
    }

    private final AuthorizationGuard guard;
    private final AuthorizationService service;
    private final AuthorizationAdminService adminService;

    public AuthorizationController(AuthorizationGuard guard, AuthorizationService service,
                                   AuthorizationAdminService adminService) {
        this.guard = guard;
        this.service = service;
        this.adminService = adminService;
    }

    @GetMapping("/permissions")
    public List<PermissionCatalog.Permission> permissions() {
        guard.require("authorization.decision.check", "authorization.permission", null, Map.of());
        return PermissionCatalog.codes().stream()
                .map(code -> PermissionCatalog.find(code).orElseThrow())
                .toList();
    }

    @PostMapping("/decisions/check")
    public AccessDecision check(@RequestBody CheckRequest request) {
        UUID actorId = request.actorId() == null ? guard.actor().actorId() : request.actorId();
        if (actorId.equals(guard.actor().actorId())) {
            return guard.decide(request.permission(), request.resourceType(), request.resourceId(),
                    request.attributes());
        }
        guard.require("authorization.decision.check", "authorization.decision", null, Map.of());
        return guard.checkFor(actorId, request.permission(), request.resourceType(),
                request.resourceId(), request.attributes());
    }

    @PostMapping("/decisions/explain")
    public AccessDecision explain(@RequestBody CheckRequest request) {
        guard.require("authorization.decision.explain", "authorization.decision", null, Map.of());
        UUID actorId = request.actorId() == null ? guard.actor().actorId() : request.actorId();
        return guard.explainFor(actorId, request.permission(), request.resourceType(),
                request.resourceId(), request.attributes());
    }

    @PostMapping("/simulations")
    public AuthorizationAdminService.SimulationResult simulate(@RequestBody SimulationRequest request) {
        guard.require("authorization.decision.explain", "authorization.decision", null, Map.of());
        guard.requireMfa();
        return adminService.simulate(new AuthorizationAdminService.SimulationCommand(
                request.grants(), request.denies(), request.checks()));
    }

    @GetMapping("/cache/version")
    public Map<String, Object> cacheVersion() {
        guard.require("authorization.cache.read", "authorization.cache", null, Map.of());
        return Map.of("version", service.currentVersion());
    }

    @GetMapping("/separation-rules")
    public Map<String, Object> separationRules() {
        guard.require("authorization.decision.check", "authorization.separation_rule", null,
                Map.of());
        return Map.of(
                "rolePairs", com.acme.platform.authorization.SodRuleCatalog.rolePairs(),
                "attributeRules",
                com.acme.platform.authorization.SodRuleCatalog.attributeRules(),
                "highPrivilegePermissions",
                com.acme.platform.authorization.SodRuleCatalog.highPrivilegePermissions(),
                "managementPermissions",
                com.acme.platform.authorization.SodRuleCatalog.managementPermissions());
    }

    @GetMapping("/separation-conflicts")
    public List<AuthorizationAdminService.SeparationConflictView> separationConflicts() {
        guard.require("authorization.review.manage", "authorization.separation_rule", null,
                Map.of());
        return adminService.separationConflicts();
    }

    @GetMapping("/audit/decisions")
    public List<DecisionLogView> audit(
            @RequestParam(defaultValue = "50") int limit) {
        guard.require("authorization.audit.read", "authorization.audit", null, Map.of());
        return service.audit(Math.max(1, Math.min(limit, 200))).stream()
                .map(row -> new DecisionLogView(row.id(), row.actorId(),
                        row.permissionCode(), row.resourceType(), row.resourceId(), row.allowed(),
                        row.reasonCode(), row.factors(), row.occurredAt()))
                .toList();
    }
}
