package com.acme.platform.authorization;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 字段策略求值：记录级允许不能覆盖字段级拒绝。
 *
 * <p>人员目录的默认规则写在代码里（公开字段可看、邮箱默认不可看），
 * 租户的 allow/deny 策略在此之上叠加，deny 永远最后生效。
 */
@Component
public class FieldPolicyEvaluator {

    private static final Set<String> EMAIL_ROLES = Set.of(
            SystemRoleCatalog.HR_ADMIN, SystemRoleCatalog.BUSINESS_OWNER,
            SystemRoleCatalog.TENANT_OWNER);

    private final AuthorizationStore store;

    public FieldPolicyEvaluator(AuthorizationStore store) {
        this.store = store;
    }

    public Map<String, FieldAccess> rules(Collection<String> roleCodes, String resourceType) {
        if (!"directory.person".equals(resourceType)) {
            return Map.of();
        }
        Map<String, FieldAccess> rules = new LinkedHashMap<>();
        rules.put("displayName", new FieldAccess(true, false, true, true));
        rules.put("status", new FieldAccess(true, false, true, true));
        boolean emailAllowed = roleCodes.stream().anyMatch(EMAIL_ROLES::contains);
        rules.put("primaryEmail", emailAllowed
                ? new FieldAccess(true, false, true, false)
                : FieldAccess.none());

        for (var policy : store.fieldPolicies(roleCodes, resourceType)) {
            FieldAccess current = rules.getOrDefault(policy.fieldName(), FieldAccess.none());
            if ("deny".equals(policy.effect())) {
                rules.put(policy.fieldName(), FieldAccess.none());
            } else {
                rules.put(policy.fieldName(), new FieldAccess(
                        current.view() || policy.canView(),
                        current.edit() || policy.canEdit(),
                        current.export() || policy.canExport(),
                        current.ai() || policy.canUseAi()));
            }
        }
        return rules;
    }
}
