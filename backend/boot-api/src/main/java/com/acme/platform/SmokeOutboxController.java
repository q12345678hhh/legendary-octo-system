package com.acme.platform;

import com.acme.platform.platform.OutboxEventPublisher;
import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 冒烟入口：验证「请求 → 事务 outbox → Worker 消费」的完整链路。
 *
 * <p>默认关闭，只有 {@code platform.smoke.enabled=true} 时才注册，避免把调试端点带进正常环境。
 * 位于根包（装配层），不属于任何业务模块。
 */
@RestController
@RequestMapping("/api/v1/system")
@ConditionalOnProperty(name = "platform.smoke.enabled", havingValue = "true")
class SmokeOutboxController {

    /** 与 boot-worker 的 SmokePingEventHandler 约定同名，契约见 contracts/events/envelope.v1.json。 */
    private static final String SMOKE_PING_EVENT = "platform.smoke.ping.v1";

    private final OutboxEventPublisher publisher;

    SmokeOutboxController(OutboxEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/smoke/ping-events")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> ping(@RequestBody(required = false) Map<String, Object> body) {
        RequestContext context = RequestContextHolder.current();
        if (context.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少合法的 X-Tenant-Id");
        }

        Object message = body == null ? null : body.get("message");
        UUID eventId = publisher.publish(SMOKE_PING_EVENT,
                Map.of("message", message == null ? "手动冒烟" : message.toString()));

        return Map.of(
                "eventId", eventId,
                "tenantId", context.tenantId(),
                "correlationId", context.correlationId(),
                "traceId", context.traceId());
    }
}
