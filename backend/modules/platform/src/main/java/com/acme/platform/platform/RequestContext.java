package com.acme.platform.platform;

import java.util.UUID;

/**
 * 统一请求上下文：租户、关联 ID、链路 trace ID。
 *
 * <p>一次请求只有一个上下文，API、数据库写入与 outbox 事件共用同一组标识，
 * 从而把页面、API、数据库与 Worker 串起来。
 *
 * @param tenantId     租户标识；系统级请求可以为空，写入租户数据的事件必须非空
 * @param correlationId 关联 ID，跨进程串联同一条业务链路
 * @param traceId      链路 trace ID，32 位小写十六进制，与 W3C traceparent 的格式一致
 */
public record RequestContext(UUID tenantId, String correlationId, String traceId) {
}
