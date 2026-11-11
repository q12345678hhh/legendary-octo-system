package com.acme.platform.platform;

import java.util.UUID;

/**
 * 已认证主体：由会话或 API 令牌解析得到，供应用服务做授权与审计。
 *
 * <p>与 {@link RequestContext} 的区别：后者是所有请求都有的轻量上下文（含匿名请求），
 * 本记录只有认证成功后才存在，且不含任何凭据。
 *
 * @param tenantId    认证发生的租户，与请求头 {@code X-Tenant-Id} 一致
 * @param actorId     统一主体 ID（用户、服务账号、AI 或外部协作者）
 * @param userId      登录账号 ID；服务账号为空
 * @param memberId    租户成员 ID；服务账号为空
 * @param actorType   user / service / ai / external
 * @param mfaSatisfied 本次会话是否已完成第二因子
 * @param sessionId   浏览器会话 ID；Bearer 令牌为空
 */
public record ActorContext(UUID tenantId,
                           UUID actorId,
                           UUID userId,
                           UUID memberId,
                           String actorType,
                           boolean mfaSatisfied,
                           UUID sessionId) {
}
