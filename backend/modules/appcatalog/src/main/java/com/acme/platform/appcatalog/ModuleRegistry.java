package com.acme.platform.appcatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 模块清单与运行时装配映射。
 *
 * <p>模块 code 对应一组 API 路径前缀：请求命中停用模块时由
 * {@code ModuleActivationFilter} 拒绝。未登记的路径（认证、健康检查、系统作业等）
 * 不属于任何业务模块，永不拦截。
 */
public final class ModuleRegistry {

    /** 模块定义：基础模块默认为新租户启用；pathPrefixes 按最长前缀优先匹配。 */
    public record Definition(String code, String name, boolean baseModule,
                             Set<String> permissionPrefixes, List<String> pathPrefixes) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("platform.workbench", "工作台", true,
                    Set.of("business.work"),
                    List.of("/api/v1/me/work-page", "/api/v1/me/work-access-log",
                            "/api/v1/people")),
            new Definition("organization", "组织与人员", true,
                    Set.of("organization.structure", "department.workspace"),
                    List.of("/api/v1/departments", "/api/v1/org-units", "/api/v1/legal-entities",
                            "/api/v1/jobs", "/api/v1/job-levels", "/api/v1/positions",
                            "/api/v1/cost-centers", "/api/v1/locations", "/api/v1/members")),
            new Definition("appcatalog", "模块中心", true,
                    Set.of("appcatalog"),
                    List.of("/api/v1/app-catalog")),
            new Definition("privacy", "隐私与数据", true,
                    Set.of("privacy"),
                    List.of("/api/v1/privacy", "/api/v1/retention")),
            new Definition("worklog", "工时与周报", true,
                    Set.of("worklog"),
                    List.of("/api/v1/worklog")),
            new Definition("expense", "单据预审", true,
                    Set.of("expense"),
                    List.of("/api/v1/expense")),
            new Definition("collaboration", "协作", true,
                    Set.of("collaboration"),
                    List.of("/api/v1/collaboration", "/api/v1/notifications")),
            new Definition("workflow", "审批流", true,
                    Set.of("workflow"),
                    List.of("/api/v1/workflow")),
            new Definition("insights", "管理洞察", true,
                    Set.of("insight"),
                    List.of("/api/v1/insights")));

    private ModuleRegistry() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Set<String> baseCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (Definition definition : DEFINITIONS) {
            if (definition.baseModule()) {
                codes.add(definition.code());
            }
        }
        return codes;
    }

    public static boolean isKnown(String code) {
        return DEFINITIONS.stream().anyMatch(definition -> definition.code().equals(code));
    }

    public static boolean isBase(String code) {
        return DEFINITIONS.stream()
                .anyMatch(definition -> definition.baseModule() && definition.code().equals(code));
    }

    /** 请求路径命中的模块；未登记路径返回空，调用方必须放行。 */
    public static Optional<String> moduleForPath(String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        String matchedCode = null;
        int matchedLength = -1;
        for (Definition definition : DEFINITIONS) {
            for (String prefix : definition.pathPrefixes()) {
                if (matches(path, prefix) && prefix.length() > matchedLength) {
                    matchedCode = definition.code();
                    matchedLength = prefix.length();
                }
            }
        }
        return Optional.ofNullable(matchedCode);
    }

    /** 前缀匹配必须落在路径分隔符边界上，避免 /api/v1/jobs 命中 /api/v1/jobs-archive。 */
    private static boolean matches(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    /** 供前端清单与文档使用的有序 code 列表。 */
    public static List<String> codes() {
        List<String> codes = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            codes.add(definition.code());
        }
        return List.copyOf(codes);
    }
}
