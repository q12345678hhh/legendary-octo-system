package com.acme.platform.authorization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内置系统角色模板：写在代码里，租户不能直接修改，只能复制权限集合后收窄。
 *
 * <p>管理员角色只覆盖管理命名空间，不包含业务正文；老板访问全租户工作数据由
 * {@code organization_owner_role} 派生 {@code business-owner}，与 WP02-03 的边界一致。
 */
public final class SystemRoleCatalog {

    public record RoleTemplate(String code, String name, Set<String> permissions) {
    }

    public static final String TENANT_OWNER = "tenant-owner";
    public static final String TENANT_ADMIN = "tenant-admin";
    public static final String BUSINESS_OWNER = "business-owner";
    public static final String SECURITY_ADMIN = "security-admin";
    public static final String HR_ADMIN = "hr-admin";
    public static final String MANAGER = "manager";
    public static final String DELEGATE_MANAGER = "delegate-manager";
    public static final String EMPLOYEE = "employee";

    private static final Map<String, RoleTemplate> ROLES;

    static {
        Map<String, RoleTemplate> map = new LinkedHashMap<>();
        map.put(TENANT_OWNER, new RoleTemplate(TENANT_OWNER, "租户所有者", Set.of(
                "tenant.admin", "identity.member.manage",
                "organization.structure.read", "organization.structure.manage",
                "directory.person.read", "directory.employment.read", "directory.person.export",
                "file.read", "file.manage", "search.read", "report.read", "report.export",
                "ai.context.read",
                "authorization.role.manage", "authorization.assignment.manage",
                "authorization.deny.manage", "authorization.policy.manage",
                "authorization.decision.check", "authorization.decision.explain",
                "authorization.audit.read", "authorization.cache.read")));
        map.put(TENANT_ADMIN, new RoleTemplate(TENANT_ADMIN, "租户管理员", Set.of(
                "tenant.admin", "identity.member.manage",
                "organization.structure.read", "organization.structure.manage",
                "directory.person.read", "directory.employment.read",
                "file.read", "search.read", "report.read")));
        map.put(BUSINESS_OWNER, new RoleTemplate(BUSINESS_OWNER, "企业老板", Set.of(
                "business.work.read", "directory.person.read", "directory.employment.read",
                "directory.person.export", "file.read", "search.read",
                "report.read", "report.export", "ai.context.read")));
        map.put(SECURITY_ADMIN, new RoleTemplate(SECURITY_ADMIN, "安全管理员", Set.of(
                "authorization.role.manage", "authorization.assignment.manage",
                "authorization.deny.manage", "authorization.policy.manage",
                "authorization.decision.check", "authorization.decision.explain",
                "authorization.audit.read", "authorization.cache.read")));
        map.put(HR_ADMIN, new RoleTemplate(HR_ADMIN, "HR 核心管理员", Set.of(
                "directory.person.read", "directory.employment.read",
                "directory.person.manage", "directory.person.export",
                "organization.structure.read", "file.read", "search.read", "report.read")));
        map.put(MANAGER, new RoleTemplate(MANAGER, "部门主管", Set.of(
                "directory.person.read", "business.work.read")));
        map.put(DELEGATE_MANAGER, new RoleTemplate(DELEGATE_MANAGER, "代管人", Set.of(
                "directory.person.read", "business.work.read")));
        map.put(EMPLOYEE, new RoleTemplate(EMPLOYEE, "普通员工", Set.of(
                "directory.person.read", "file.read")));
        ROLES = Collections.unmodifiableMap(map);
    }

    private SystemRoleCatalog() {
    }

    public static boolean exists(String code) {
        return code != null && ROLES.containsKey(code);
    }

    public static Optional<RoleTemplate> find(String code) {
        return Optional.ofNullable(code == null ? null : ROLES.get(code));
    }

    public static Set<String> permissions(String code) {
        return find(code).map(RoleTemplate::permissions).orElse(Set.of());
    }

    public static List<RoleTemplate> all() {
        return List.copyOf(ROLES.values());
    }

    /** 高权限角色：临时授权必须有到期时间，委托也不得无限期。 */
    public static boolean highRisk(String code) {
        Set<String> permissions = permissions(code);
        return permissions.contains("tenant.admin")
                || permissions.contains("authorization.assignment.manage")
                || permissions.contains("authorization.role.manage");
    }
}
