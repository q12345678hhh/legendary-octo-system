package com.acme.platform.worklog.device;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 片段元数据写入；唯一键保证客户端重传不会产生重复行。 */
@Component
public class SegmentStore {

    private final JdbcTemplate jdbcTemplate;

    public SegmentStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return true 表示新插入，false 表示命中已有 client_segment_id（重传）。 */
    public boolean insert(UUID tenantId, UUID deviceId, UUID clientSegmentId,
                          OffsetDateTime capturedFrom, OffsetDateTime capturedTo,
                          String sourceType, String summary, int policyVersion) {
        return jdbcTemplate.update("""
                INSERT INTO work_assistant_segment
                    (tenant_id, device_id, client_segment_id, captured_from, captured_to,
                     source_type, summary, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, device_id, client_segment_id) DO NOTHING
                """, tenantId, deviceId, clientSegmentId, capturedFrom, capturedTo,
                sourceType, summary, policyVersion) > 0;
    }
}
