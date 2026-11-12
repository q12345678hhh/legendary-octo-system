package com.acme.platform.authorization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 代码内置的职责分离规则：租户只读查看，不能自行放宽。
 *
 * <p>两类规则：
 * <ol>
 *   <li>静态互斥角色对：薪酬四人四职、插件上传/安装、AI 工具注册/评级/启用、模块配置/批准，
 *       以及审计员与任何管理类角色的互斥；</li>
 *   <li>动态属性规则：同一人不能既是制单人又是复核人/批准人，属性缺失按不通过处理。</li>
 * </ol>
 */
public final class SodRuleCatalog {

    public record RolePair(String group, String left, String right) {
    }

    public record AttributeRule(String permission, List<String> attributes, boolean onlyWhenHighRisk) {
    }

    public record Conflict(String group, String code, String detail) {
    }

    public static final String AUDITOR = "auditor";

    public static final String SOD_ROLE_CONFLICT = "SOD_ROLE_CONFLICT";
    public static final String SOD_ATTRIBUTE_MISSING = "SOD_ATTRIBUTE_MISSING";
    public static final String SOD_CONFLICT = "SOD_CONFLICT";

    private static final List<RolePair> ROLE_PAIRS = List.of(
            new RolePair("payroll", "payroll-preparer", "payroll-reviewer"),
            new RolePair("payroll", "payroll-preparer", "payroll-approver"),
            new RolePair("payroll", "payroll-preparer", "payroll-exporter"),
            new RolePair("payroll", "payroll-reviewer", "payroll-approver"),
            new RolePair("payroll", "payroll-reviewer", "payroll-exporter"),
            new RolePair("payroll", "payroll-approver", "payroll-exporter"),
            new RolePair("plugin", "plugin-publisher", "plugin-installer"),
            new RolePair("ai", "ai-tool-developer", "ai-tool-reviewer"),
            new RolePair("ai", "ai-tool-developer", "ai-tool-enabler"),
            new RolePair("module", "module-configurer", "module-approver"));

    /** 审计员不能兼任的管理类权限：命中任意一个即视为管理角色。 */
    private static final Set<String> MANAGEMENT_PERMISSIONS = Set.of(
            "tenant.admin", "authorization.access.approve", "authorization.review.manage",
            "authorization.role.manage", "authorization.assignment.manage",
            "authorization.deny.manage", "authorization.policy.manage",
            "organization.structure.manage", "directory.person.manage",
            "payroll.run.prepare", "payroll.run.review", "payroll.run.approve", "payroll.export",
            "plugin.upload", "plugin.install",
            "ai.tool.register", "ai.tool.rate", "ai.tool.enable",
            "module.activation.configure");

    private static final Set<String> HIGH_PRIVILEGE_PERMISSIONS = Set.of(
            "tenant.admin", "authorization.role.manage", "authorization.assignment.manage",
            "authorization.deny.manage", "authorization.policy.manage",
            "authorization.access.approve", "payroll.run.approve", "payroll.export",
            "plugin.install", "ai.tool.enable", "module.activation.approve");

    private static final Map<String, AttributeRule> ATTRIBUTE_RULES;

    static {
        Map<String, AttributeRule> map = new LinkedHashMap<>();
        map.put("authorization.access.approve",
                new AttributeRule("authorization.access.approve", List.of("requestedBy"), false));
        map.put("authorization.assignment.manage",
                new AttributeRule("authorization.assignment.manage",
                        List.of("targetActorId"), true));
        map.put("payroll.run.review",
                new AttributeRule("payroll.run.review", List.of("preparedBy"), false));
        map.put("payroll.run.approve",
                new AttributeRule("payroll.run.approve", List.of("preparedBy", "reviewedBy"), false));
        map.put("payroll.export",
                new AttributeRule("payroll.export",
                        List.of("preparedBy", "reviewedBy", "approvedBy"), false));
        map.put("plugin.install",
                new AttributeRule("plugin.install", List.of("uploadedBy"), false));
        map.put("ai.tool.rate",
                new AttributeRule("ai.tool.rate", List.of("registeredBy"), false));
        map.put("ai.tool.enable",
                new AttributeRule("ai.tool.enable", List.of("registeredBy", "ratedBy"), false));
        map.put("module.activation.approve",
                new AttributeRule("module.activation.approve", List.of("configuredBy"), false));
        map.put("authorization.review.decide",
                new AttributeRule("authorization.review.decide", List.of("subjectActorId"), false));
        ATTRIBUTE_RULES = Map.copyOf(map);
    }

    private SodRuleCatalog() {
    }

    public static List<RolePair> rolePairs() {
        return ROLE_PAIRS;
    }

    public static List<AttributeRule> attributeRules() {
        return List.copyOf(ATTRIBUTE_RULES.values());
    }

    public static Set<String> highPrivilegePermissions() {
        return HIGH_PRIVILEGE_PERMISSIONS;
    }

    public static Set<String> managementPermissions() {
        return MANAGEMENT_PERMISSIONS;
    }

    /** 角色权限集合与高权限权限码相交即为高权限角色。 */
    public static boolean highPrivilege(String roleCode) {
        Set<String> permissions = SystemRoleCatalog.permissions(roleCode);
        for (String permission : permissions) {
            if (HIGH_PRIVILEGE_PERMISSIONS.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定一次访问是否触发职责分离冲突。
     *
     * <p>{@code allRoles} 是主体当前全部角色（含派生与自定义），{@code matchingRoles} 是
     * 命中本次权限的角色。静态规则按全部角色判定，动态规则只看请求属性。
     */
    public static Optional<Conflict> conflict(UUID actorId, Set<String> matchingRoles,
                                              Set<String> allRoles, String permission,
                                              Map<String, Object> attributes) {
        Optional<Conflict> roleConflict = roleConflict(allRoles, permission);
        if (roleConflict.isPresent()) {
            return roleConflict;
        }
        return attributeConflict(actorId, permission, attributes);
    }

    /** 仅按角色组合判定冲突，供授予前预检使用。 */
    public static Optional<Conflict> roleConflict(Set<String> roles) {
        Optional<RolePair> pair = conflictingPair(roles);
        if (pair.isPresent()) {
            return Optional.of(new Conflict(pair.get().group(), SOD_ROLE_CONFLICT,
                    pair.get().left() + "+" + pair.get().right()));
        }
        return conflictingManagementRole(roles)
                .map(role -> new Conflict("auditor", SOD_ROLE_CONFLICT, AUDITOR + "+" + role));
    }

    private static Optional<Conflict> roleConflict(Set<String> roles, String permission) {
        Optional<RolePair> pair = conflictingPair(roles);
        if (pair.isPresent()) {
            Set<String> permissions = union(permissions(pair.get().left()),
                    permissions(pair.get().right()));
            if (permissions.contains(permission)) {
                return Optional.of(new Conflict(pair.get().group(), SOD_ROLE_CONFLICT,
                        pair.get().left() + "+" + pair.get().right()));
            }
        }

        Optional<String> management = conflictingManagementRole(roles);
        if (management.isPresent()) {
            Set<String> permissions = union(permissions(AUDITOR), permissions(management.get()));
            if (permissions.contains(permission)) {
                return Optional.of(new Conflict("auditor", SOD_ROLE_CONFLICT,
                        AUDITOR + "+" + management.get()));
            }
        }
        return Optional.empty();
    }

    private static Optional<RolePair> conflictingPair(Set<String> roles) {
        for (RolePair pair : ROLE_PAIRS) {
            if (roles.contains(pair.left()) && roles.contains(pair.right())) {
                return Optional.of(pair);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> conflictingManagementRole(Set<String> roles) {
        if (!roles.contains(AUDITOR)) {
            return Optional.empty();
        }
        for (String role : roles) {
            if (!AUDITOR.equals(role) && intersects(permissions(role), MANAGEMENT_PERMISSIONS)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private static Optional<Conflict> attributeConflict(UUID actorId, String permission,
                                                        Map<String, Object> attributes) {
        AttributeRule rule = ATTRIBUTE_RULES.get(permission);
        if (rule == null) {
            return Optional.empty();
        }
        if (rule.onlyWhenHighRisk() && !Boolean.TRUE.equals(asBoolean(attributes.get("highRisk")))) {
            return Optional.empty();
        }
        for (String attribute : rule.attributes()) {
            UUID value = asUuid(attributes.get(attribute));
            if (value == null) {
                return Optional.of(new Conflict("attribute", SOD_ATTRIBUTE_MISSING,
                        permission + ":" + attribute));
            }
            if (value.equals(actorId)) {
                return Optional.of(new Conflict("attribute", SOD_CONFLICT,
                        permission + ":" + attribute));
            }
        }
        return Optional.empty();
    }

    private static Set<String> permissions(String roleCode) {
        return SystemRoleCatalog.permissions(roleCode);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static boolean intersects(Set<String> values, Set<String> candidates) {
        for (String value : values) {
            if (candidates.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /** 供冲突清单与测试使用的只读视图。 */
    public static List<Map<String, Object>> describeRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (RolePair pair : ROLE_PAIRS) {
            rules.add(Map.of("group", pair.group(), "left", pair.left(), "right", pair.right()));
        }
        return rules;
    }
}
