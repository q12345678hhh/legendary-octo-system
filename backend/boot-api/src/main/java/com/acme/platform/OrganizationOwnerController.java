package com.acme.platform;

import com.acme.platform.identity.IdentityGuard;
import com.acme.platform.organization.OwnerRole;
import com.acme.platform.organization.OrganizationRelationService;
import com.acme.platform.platform.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 老板（owner）与受托人（trustee）角色，以及模块启用前的组织就绪检查。
 *
 * <p>这是独立的业务角色，不是租户管理员：租户管理员可以管理账号，
 * 但在业务正文里没有任何默认权限；正文权限只能由老板或受托人授予。
 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationOwnerController {

    private final OrganizationRelationService relationService;
    private final IdentityGuard guard;

    public OrganizationOwnerController(OrganizationRelationService relationService,
                                       IdentityGuard guard) {
        this.relationService = relationService;
        this.guard = guard;
    }

    @GetMapping("/owner-roles")
    public List<OwnerRole> ownerRoles() {
        guard.requireUser();
        return relationService.ownerRoles();
    }

    @PostMapping("/owner-roles")
    public OwnerRole assign(@RequestBody OwnerRoleRequest request) {
        guard.requireAdmin(false);
        return relationService.assignOwnerRole(new OrganizationRelationService.AssignOwnerRoleCommand(
                request.employmentId(), roleType(request.roleType()),
                request.effectiveFrom(), request.effectiveTo()));
    }

    @PostMapping("/owner-roles/{roleId}/revoke")
    public OwnerRole revoke(@PathVariable UUID roleId, @RequestBody RevokeRequest request) {
        guard.requireAdmin(false);
        return relationService.revokeOwnerRole(roleId, request.effectiveTo());
    }

    /** 就绪状态：老板为空或没有独立受托人时返回 ready=false 与可读的阻断原因。 */
    @GetMapping("/organization/readiness")
    public OrganizationRelationService.ReadinessView readiness() {
        guard.requireUser();
        return relationService.readiness();
    }

    private static OwnerRole.RoleType roleType(String value) {
        if (value == null || value.isBlank()) {
            return OwnerRole.RoleType.OWNER;
        }
        return switch (value.trim().toLowerCase()) {
            case "owner" -> OwnerRole.RoleType.OWNER;
            case "trustee" -> OwnerRole.RoleType.TRUSTEE;
            default -> throw ApiException.unprocessable("OWNER_ROLE_TYPE_INVALID",
                    "roleType 只能是 owner 或 trustee：" + value);
        };
    }

    public record OwnerRoleRequest(UUID employmentId, String roleType, LocalDate effectiveFrom,
                                   LocalDate effectiveTo) {
    }

    public record RevokeRequest(LocalDate effectiveTo) {
    }
}
