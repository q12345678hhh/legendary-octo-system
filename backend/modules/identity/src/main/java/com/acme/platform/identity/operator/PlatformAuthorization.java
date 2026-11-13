package com.acme.platform.identity.operator;

import com.acme.platform.platform.ApiException;

/**
 * 平台运营鉴权：权限判定与 MFA 门禁。
 *
 * <p>导航项可以按权限隐藏，但直接访问路由必须在这里被拒绝——这是后端唯一的鉴权点。
 */
public final class PlatformAuthorization {

    private PlatformAuthorization() {
    }

    public static void require(PlatformActorContext actor, String permission) {
        if (actor == null) {
            throw new ApiException(401, "PLATFORM_SESSION_REQUIRED", "平台接口必须使用运营账号登录");
        }
        if (!actor.permissions().contains(permission)) {
            throw ApiException.forbidden("PLATFORM_PERMISSION_DENIED",
                    "当前运营角色无权执行：" + permission);
        }
    }

    /** 写操作在权限之外还要求本次会话已完成 MFA。 */
    public static void requireManage(PlatformActorContext actor, String permission) {
        require(actor, permission);
        if (!actor.mfaSatisfied()) {
            throw ApiException.forbidden("PLATFORM_MFA_REQUIRED",
                    "高风险平台操作必须先完成 MFA 验证");
        }
    }
}
