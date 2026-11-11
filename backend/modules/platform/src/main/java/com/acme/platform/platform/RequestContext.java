package com.acme.platform.platform;

import java.util.UUID;

/**
 * 统一请求上下文：租户、认证主体、关联 ID、链路 trace ID。
 *
 * <p>一次请求只有一个上下文，API、数据库写入、outbox 事件与 Worker 消费共用同一组标识，
 * 从而把页面、API、数据库与 Worker 串起来。
 *
 * <p>{@code subjectId} 是本阶段的认证主体占位：由 {@code X-Subject-Id} 头声明，
 * 只用于骨架验证与越权负例，不是身份系统。WP02-02 会把它替换为真实会话主体，
 * 在此之前任何共享环境都必须关闭该头的信任。
 *
 * @param tenantId     租户标识；系统级请求可以为空，写入租户数据的事件必须非空
 * @param subjectId    认证主体占位；可空，但幂等、作业与文件都必须有主体才能归属
 * @param correlationId 关联 ID，跨进程串联同一条业务链路
 * @param traceId      链路 trace ID，32 位小写十六进制，与 W3C traceparent 的格式一致
 */
public record RequestContext(UUID tenantId, String subjectId, String correlationId, String traceId) {

    public RequestContext {
        subjectId = subjectId == null || subjectId.isBlank() ? null : subjectId.trim();
    }

    /** 兼容只关心租户与链路标识的调用方（事件写入、健康检查等）。 */
    public RequestContext(UUID tenantId, String correlationId, String traceId) {
        this(tenantId, null, correlationId, traceId);
    }
}
