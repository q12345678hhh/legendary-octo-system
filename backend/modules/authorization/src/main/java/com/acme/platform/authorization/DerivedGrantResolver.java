package com.acme.platform.authorization;

import com.acme.platform.identity.TenantMember;
import com.acme.platform.organization.EmploymentScope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 组织派生的授权：不落库，每次按当前组织快照重算。
 *
 * <p>成员 owner/admin 只进入管理命名空间；老板的业务范围只来自
 * {@code organization_owner_role}，与租户管理员身份无关。
 */
@Component
public class DerivedGrantResolver {

    public List<RoleGrant> resolve(TenantMember member, EmploymentScope scope) {
        List<RoleGrant> grants = new ArrayList<>();
        if (member == null) {
            return grants;
        }
        if (member.owner()) {
            grants.add(RoleGrant.derived(SystemRoleCatalog.TENANT_OWNER, ScopeType.TENANT, null,
                    "MEMBER_OWNER"));
        }
        if (member.admin()) {
            grants.add(RoleGrant.derived(SystemRoleCatalog.TENANT_ADMIN, ScopeType.TENANT, null,
                    "MEMBER_ADMIN"));
        }
        if (scope == null) {
            return grants;
        }
        grants.add(RoleGrant.derived(SystemRoleCatalog.EMPLOYEE_SELF, ScopeType.SELF,
                scope.personId(), "EMPLOYEE_SELF"));
        if (scope.primaryOrgUnitId() != null) {
            grants.add(RoleGrant.derived(SystemRoleCatalog.EMPLOYEE, ScopeType.ORG_UNIT,
                    scope.primaryOrgUnitId(), "EMPLOYEE_UNIT"));
        }
        if (scope.hasReports()) {
            grants.add(RoleGrant.derived(SystemRoleCatalog.MANAGER, ScopeType.DIRECT_REPORTS,
                    null, "MANAGER"));
            grants.add(RoleGrant.derived(SystemRoleCatalog.MANAGER, ScopeType.REPORTING_CHAIN,
                    null, "MANAGER"));
            if (scope.primaryOrgUnitId() != null) {
                grants.add(RoleGrant.derived(SystemRoleCatalog.MANAGER_UNIT,
                        ScopeType.ORG_UNIT_SUBTREE, scope.primaryOrgUnitId(), "MANAGER_UNIT"));
            }
        }
        if (scope.isBoss()) {
            grants.add(RoleGrant.derived(SystemRoleCatalog.BUSINESS_OWNER, ScopeType.TENANT, null,
                    "OWNER_ROLE"));
        }
        for (var unitId : scope.delegatedOrgUnitIds()) {
            grants.add(RoleGrant.derived(SystemRoleCatalog.DELEGATE_MANAGER,
                    ScopeType.ORG_UNIT_SUBTREE, unitId, "DELEGATION"));
        }
        return grants;
    }
}
