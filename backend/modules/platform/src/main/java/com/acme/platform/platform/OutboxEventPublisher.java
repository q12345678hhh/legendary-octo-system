package com.acme.platform.platform;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 事务 outbox 写入器：必须在调用方的事务里执行，业务数据与事件同生共死。
 *
 * <p>这里只做一次 INSERT，不做任何"发送"动作：投递由 Worker 抢占 outbox 完成，
 * 这样业务事务提交失败时不会漏发事件，也不会发出业务未落库的事件。
 */
@Component
public class OutboxEventPublisher {

    /** 与 contracts/events/envelope.v1.json 的 event_name 约束保持一致。 */
    private static final Pattern EVENT_NAME =
            Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*\\.v([0-9]+)$");

    private static final String INSERT_SQL =
            "INSERT INTO platform_outbox "
                    + "(event_id, tenant_id, event_name, schema_version, producer, "
                    + "correlation_id, trace_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String producer;

    public OutboxEventPublisher(JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                @Value("${spring.application.name:platform-api}") String producer) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.producer = producer;
    }

    /**
     * 写入一条待投递事件，返回事件 ID。
     *
     * @param eventName 形如 {@code platform.smoke.ping.v1}，版本号从后缀解析
     * @param payload   业务载荷，序列化为 outbox 的 payload 列
     */
    public UUID publish(String eventName, Object payload) {
        int schemaVersion = schemaVersion(eventName);
        RequestContext context = RequestContextHolder.current();
        if (context.tenantId() == null) {
            throw new IllegalStateException("事件必须归属租户：请求缺少合法的 X-Tenant-Id");
        }

        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(INSERT_SQL,
                eventId,
                context.tenantId(),
                eventName,
                schemaVersion,
                producer,
                context.correlationId(),
                context.traceId(),
                OffsetDateTime.now(ZoneOffset.UTC),
                toJson(payload));
        return eventId;
    }

    private static int schemaVersion(String eventName) {
        Matcher matcher = EVENT_NAME.matcher(Objects.requireNonNull(eventName, "eventName"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "事件名不符合 <domain>.<action>.v<version> 约定: " + eventName);
        }
        return Integer.parseInt(matcher.group(2));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(payload, "payload"));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("事件载荷无法序列化为 JSON", e);
        }
    }
}
