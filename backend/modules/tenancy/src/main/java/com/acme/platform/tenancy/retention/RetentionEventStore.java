package com.acme.platform.tenancy.retention;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 租户侧留痕：只追加到 {@code retention_event}，表上有防改删触发器。
 */
@Component
public class RetentionEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RetentionEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(UUID tenantId, String action, String dataClass, UUID objectId, String actor,
                       Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化保留事件详情", e);
        }
        jdbcTemplate.update("""
                INSERT INTO retention_event (tenant_id, action, data_class, object_id, actor, detail)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, action, dataClass, objectId, actor, json);
    }
}
