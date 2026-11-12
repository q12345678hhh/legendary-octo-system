package com.acme.platform.authorization;

/**
 * 职责分离检查扩展点。
 *
 * <p>WP02-04 只固定调用位置与默认放行；具体冲突规则（权限申请/批准、薪酬制单/复核/批准、
 * 插件上传/安装、AI 工具注册/启用）由 WP02-05 实现并注入。
 */
@FunctionalInterface
public interface SeparationOfDutyChecker {

    boolean conflicts(AuthorizationSubject subject, String permission);

    /** 判定所需的最小主体视图，避免把内部记录暴露给后续模块。 */
    record AuthorizationSubject(java.util.UUID actorId, java.util.Set<String> roleCodes) {
    }
}
