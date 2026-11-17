package com.acme.platform.worklog.device;

import com.acme.platform.platform.retention.RetentionTarget;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作助手语音片段接入回收站：回收只写 deleted_at，清理时才删行。
 */
@Component
public class SegmentRetentionTarget implements RetentionTarget {

    private final JdbcTemplate jdbcTemplate;

    public SegmentRetentionTarget(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String dataClass() {
        return "work_assistant_segment";
    }

    @Override
    public Optional<Map<String, Object>> snapshot(UUID objectId) {
        return jdbcTemplate.query("""
                        SELECT source_type, captured_from, captured_to, received_at
                        FROM work_assistant_segment WHERE id = ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("sourceType", rs.getString("source_type"));
                    snapshot.put("capturedFrom", String.valueOf(rs.getObject("captured_from")));
                    snapshot.put("capturedTo", String.valueOf(rs.getObject("captured_to")));
                    snapshot.put("receivedAt", String.valueOf(rs.getObject("received_at")));
                    return snapshot;
                }, objectId)
                .stream().findFirst();
    }

    @Override
    public void markRecycled(UUID objectId) {
        jdbcTemplate.update("""
                UPDATE work_assistant_segment SET deleted_at = now()
                WHERE id = ? AND deleted_at IS NULL
                """, objectId);
    }

    @Override
    public void restore(UUID objectId) {
        jdbcTemplate.update("""
                UPDATE work_assistant_segment SET deleted_at = NULL
                WHERE id = ? AND deleted_at IS NOT NULL
                """, objectId);
    }

    @Override
    public void purge(UUID objectId) {
        jdbcTemplate.update("DELETE FROM work_assistant_segment WHERE id = ?", objectId);
    }
}
