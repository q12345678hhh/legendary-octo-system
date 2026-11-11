package com.acme.platform.identity;

import java.util.UUID;

/**
 * 已认证主体视图：认证过滤器写入线程，控制器用它做最小授权与 /me 输出。
 *
 * <p>不包含任何凭据；{@code mfaSatisfied} 用于高权限动作的第二因子门禁。
 */
public record SessionPrincipal(UUID tenantId,
                               UUID actorId,
                               UUID userId,
                               UUID memberId,
                               UUID sessionId,
                               String actorType,
                               String email,
                               String displayName,
                               boolean mfaSatisfied,
                               boolean owner,
                               boolean admin,
                               boolean mfaRequired) {

    public boolean isService() {
        return "service".equals(actorType);
    }

    public boolean privileged() {
        return owner || admin;
    }
}
