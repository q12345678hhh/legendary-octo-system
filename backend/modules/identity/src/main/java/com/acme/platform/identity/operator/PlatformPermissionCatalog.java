package com.acme.platform.identity.operator;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台运营权限目录：代码内置，租户不可见也不可修改。
 *
 * <p>角色到权限的映射是导航项隐藏与后端鉴权的唯一来源：前端按
 * {@code /platform/api/v1/auth/me} 返回的权限集合渲染菜单，后端仍逐个端点校验。
 */
public final class PlatformPermissionCatalog {

    public static final String OVERVIEW_READ = "platform.overview.read";
    public static final String TENANT_READ = "platform.tenant.read";
    public static final String TENANT_MANAGE = "platform.tenant.manage";
    public static final String SUPPORT_MANAGE = "platform.support.manage";
    public static final String CATALOG_READ = "platform.catalog.read";
    public static final String CATALOG_MANAGE = "platform.catalog.manage";
    public static final String SECURITY_READ = "platform.security.read";
    public static final String SECURITY_MANAGE = "platform.security.manage";
    public static final String OPERATIONS_READ = "platform.operations.read";
    public static final String OPERATIONS_MANAGE = "platform.operations.manage";
    public static final String SETTINGS_READ = "platform.settings.read";
    public static final String SETTINGS_MANAGE = "platform.settings.manage";

    public static final List<String> ALL = List.of(
            OVERVIEW_READ, TENANT_READ, TENANT_MANAGE, SUPPORT_MANAGE,
            CATALOG_READ, CATALOG_MANAGE, SECURITY_READ, SECURITY_MANAGE,
            OPERATIONS_READ, OPERATIONS_MANAGE, SETTINGS_READ, SETTINGS_MANAGE);

    private static final Map<String, Set<String>> ROLES = Map.of(
            "platform-owner", Set.copyOf(ALL),
            "platform-ops", Set.of(OVERVIEW_READ, TENANT_READ, TENANT_MANAGE, SUPPORT_MANAGE,
                    CATALOG_READ, OPERATIONS_READ),
            "platform-security", Set.of(OVERVIEW_READ, SECURITY_READ, SECURITY_MANAGE,
                    OPERATIONS_READ),
            "platform-auditor", Set.of(OVERVIEW_READ, TENANT_READ, CATALOG_READ, SECURITY_READ,
                    OPERATIONS_READ, SETTINGS_READ));

    private PlatformPermissionCatalog() {
    }

    public static boolean validRole(String roleCode) {
        return roleCode != null && ROLES.containsKey(roleCode);
    }

    public static Set<String> permissionsOf(String roleCode) {
        return ROLES.getOrDefault(roleCode, Set.of());
    }

    /** 写权限统一以 {@code *.manage} 结尾，MFA 门禁据此判定。 */
    public static boolean isManage(String permission) {
        return permission != null && permission.endsWith(".manage");
    }
}
