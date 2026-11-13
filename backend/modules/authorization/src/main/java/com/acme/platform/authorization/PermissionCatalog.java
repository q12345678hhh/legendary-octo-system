package com.acme.platform.authorization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 产品级权限目录：与迁移 {@code authorization_permission} 保持一致。
 *
 * <p>目录在代码中同样是白名单：授权记录只能引用这里存在的权限码，
 * 数据库外键再兜一层，避免拼写错误静默变成「永不命中」。
 */
public final class PermissionCatalog {

    public record Permission(String code, String resourceType, String action, boolean sensitive) {
    }

    private static final Map<String, Permission> PERMISSIONS;

    static {
        Map<String, Permission> map = new LinkedHashMap<>();
        register(map, "directory.person.read", "directory.person", "read", false);
        register(map, "directory.employment.read", "directory.employment", "read", true);
        register(map, "directory.person.manage", "directory.person", "manage", true);
        register(map, "directory.person.export", "directory.person", "export", true);
        register(map, "organization.structure.read", "organization.structure", "read", false);
        register(map, "organization.structure.manage", "organization.structure", "manage", true);
        register(map, "file.read", "file", "read", true);
        register(map, "file.manage", "file", "manage", true);
        register(map, "search.read", "search", "read", false);
        register(map, "report.read", "report", "read", false);
        register(map, "report.export", "report", "export", true);
        register(map, "ai.context.read", "ai.context", "read", true);
        register(map, "authorization.role.manage", "authorization.role", "manage", true);
        register(map, "authorization.assignment.manage", "authorization.assignment", "manage", true);
        register(map, "authorization.deny.manage", "authorization.deny", "manage", true);
        register(map, "authorization.policy.manage", "authorization.policy", "manage", true);
        register(map, "authorization.decision.check", "authorization.decision", "check", true);
        register(map, "authorization.decision.explain", "authorization.decision", "explain", true);
        register(map, "authorization.audit.read", "authorization.audit", "read", true);
        register(map, "authorization.cache.read", "authorization.cache", "read", false);
        register(map, "tenant.admin", "tenant", "admin", true);
        register(map, "identity.member.manage", "identity.member", "manage", true);
        register(map, "business.work.read", "business.work", "read", true);
        register(map, "authorization.access.request", "authorization.access", "request", true);
        register(map, "authorization.access.approve", "authorization.access", "approve", true);
        register(map, "authorization.review.read", "authorization.review", "read", true);
        register(map, "authorization.review.decide", "authorization.review", "decide", true);
        register(map, "authorization.review.manage", "authorization.review", "manage", true);
        register(map, "authorization.admin_action.read", "authorization.admin_action", "read", true);
        register(map, "payroll.run.prepare", "payroll.run", "prepare", true);
        register(map, "payroll.run.review", "payroll.run", "review", true);
        register(map, "payroll.run.approve", "payroll.run", "approve", true);
        register(map, "payroll.export", "payroll", "export", true);
        register(map, "plugin.upload", "plugin", "upload", true);
        register(map, "plugin.install", "plugin", "install", true);
        register(map, "ai.tool.register", "ai.tool", "register", true);
        register(map, "ai.tool.rate", "ai.tool", "rate", true);
        register(map, "ai.tool.enable", "ai.tool", "enable", true);
        register(map, "module.activation.configure", "module.activation", "configure", true);
        register(map, "module.activation.approve", "module.activation", "approve", true);
        register(map, "department.workspace.read", "department.workspace", "read", false);
        register(map, "department.workspace.manage", "department.workspace", "manage", true);
        register(map, "worklog.time.read", "worklog.time", "read", true);
        register(map, "worklog.time.write", "worklog.time", "write", false);
        register(map, "worklog.time.review", "worklog.time", "review", true);
        register(map, "worklog.time.export", "worklog.time", "export", true);
        register(map, "worklog.report.read", "worklog.report", "read", true);
        register(map, "worklog.report.submit", "worklog.report", "submit", false);
        register(map, "worklog.report.review", "worklog.report", "review", true);
        register(map, "insight.work.read", "insight.work", "read", true);
        register(map, "insight.work.export", "insight.work", "export", true);
        register(map, "collaboration.channel.read", "collaboration.channel", "read", false);
        register(map, "collaboration.channel.manage", "collaboration.channel", "manage", true);
        register(map, "collaboration.post.write", "collaboration.post", "write", false);
        register(map, "collaboration.task.write", "collaboration.task", "write", false);
        register(map, "collaboration.page.manage", "collaboration.page", "manage", true);
        register(map, "workflow.definition.manage", "workflow.definition", "manage", true);
        register(map, "workflow.instance.start", "workflow.instance", "start", false);
        register(map, "workflow.task.act", "workflow.task", "act", false);
        register(map, "workflow.instance.admin", "workflow.instance", "admin", true);
        register(map, "appcatalog.module.read", "appcatalog.module", "read", false);
        register(map, "appcatalog.suggestion.create", "appcatalog.suggestion", "create", false);
        register(map, "appcatalog.suggestion.review", "appcatalog.suggestion", "review", true);
        register(map, "appcatalog.module.manage", "appcatalog.module", "manage", true);
        PERMISSIONS = Collections.unmodifiableMap(map);
    }

    private PermissionCatalog() {
    }

    private static void register(Map<String, Permission> map, String code, String resourceType,
                                 String action, boolean sensitive) {
        map.put(code, new Permission(code, resourceType, action, sensitive));
    }

    public static boolean exists(String code) {
        return code != null && PERMISSIONS.containsKey(code);
    }

    public static Optional<Permission> find(String code) {
        return Optional.ofNullable(code == null ? null : PERMISSIONS.get(code));
    }

    public static Set<String> codes() {
        return PERMISSIONS.keySet();
    }
}
