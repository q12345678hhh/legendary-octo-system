package com.acme.platform.identity;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 认证审计：只记录事件与结果，绝不写密码、令牌明文或 TOTP 密钥。
 *
 * <p>关联 ID 与 trace ID 取自当前请求上下文，便于和日志、追踪对齐。
 */
@Component
public class AuthEventStore {

    private final JdbcTemplate jdbcTemplate;

    public AuthEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String eventType, boolean success, UUID userId, UUID actorId,
                       String ip, String userAgent) {
        Optional<RequestContext> context = RequestContextHolder.find();
        String correlationId = context.map(RequestContext::correlationId).orElse(null);
        String traceId = context.map(RequestContext::traceId).orElse(null);
        jdbcTemplate.update("""
                INSERT INTO identity_auth_event
                    (tenant_id, actor_id, user_id, event_type, outcome, ip, user_agent,
                     detail, correlation_id, trace_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?,
                        '{}'::jsonb, ?, ?)
                """, actorId, userId, eventType, success ? "success" : "failure",
                ip, userAgent, correlationId, traceId);
    }
}
