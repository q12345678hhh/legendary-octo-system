package com.acme.platform.platform.retention;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 删除墓碑与重放记录的 JDBC 存储。
 *
 * <p>墓碑表只允许 INSERT（数据库触发器兜底），重放结果单独写在
 * {@code platform_tombstone_replay}，因此墓碑可以重复重放且结果可核对。
 */
@Component
public class TombstoneStore {

    public record Tombstone(UUID id, UUID tenantId, String dataClass, UUID objectId,
                            String subjectHash, String action, UUID requestId, OffsetDateTime createdAt) {
    }

    private static final RowMapper<Tombstone> MAPPER = (rs, rowNum) -> new Tombstone(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("data_class"),
            rs.getObject("object_id", UUID.class),
            rs.getString("subject_hash"),
            rs.getString("action"),
            rs.getObject("request_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TombstoneStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(UUID tenantId, String dataClass, UUID objectId, String subjectHash,
                       String action, UUID requestId, Map<String, Object> detail) {
        jdbcTemplate.update("""
                INSERT INTO platform_deletion_tombstone
                    (tenant_id, data_class, object_id, subject_hash, action, request_id, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, dataClass, objectId, subjectHash, action, requestId, toJson(detail));
    }

    public List<Tombstone> listRecent(int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM platform_deletion_tombstone
                        ORDER BY created_at
                        LIMIT ?
                        """, MAPPER, Math.clamp(limit, 1, 5000));
    }

    public void recordReplay(UUID tombstoneId, UUID tenantId, String outcome, Map<String, Object> detail) {
        jdbcTemplate.update("""
                INSERT INTO platform_tombstone_replay (tombstone_id, tenant_id, outcome, detail)
                VALUES (?, ?, ?, ?::jsonb)
                """, tombstoneId, tenantId, outcome, toJson(detail));
    }

    public int replayCount(UUID tombstoneId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_tombstone_replay WHERE tombstone_id = ?", Integer.class, tombstoneId);
        return count == null ? 0 : count;
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化墓碑详情", e);
        }
    }
}
