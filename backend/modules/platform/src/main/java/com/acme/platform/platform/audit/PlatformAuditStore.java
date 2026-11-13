package com.acme.platform.platform.audit;

import com.acme.platform.platform.RequestContext;
import com.acme.platform.platform.RequestContextHolder;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台级操作审计写入。
 *
 * <p>只在平台域使用：明细里禁止出现租户业务正文，最多记录对象 ID、数量与结果码。
 * 表由数据库触发器保证只能插入，应用侧没有更新/删除方法。
 */
@Component
public class PlatformAuditStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PlatformAuditStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(PlatformOperatorRef operator, String action, String targetType,
                       String targetId, Map<String, Object> detail) {
        RequestContext context = RequestContextHolder.find().orElse(null);
        jdbcTemplate.update("""
                INSERT INTO platform_audit_event
                    (operator_id, action, target_type, target_id, detail, correlation_id, trace_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, operator == null ? null : operator.operatorId(), action, targetType, targetId,
                json(detail), context == null ? null : context.correlationId(),
                context == null ? null : context.traceId());
    }

    private String json(Map<String, Object> detail) {
        return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
    }
}
