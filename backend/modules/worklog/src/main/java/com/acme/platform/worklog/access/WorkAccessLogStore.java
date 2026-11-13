package com.acme.platform.worklog.access;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 工作访问留痕存储：只插入与查询，更新/删除由数据库触发器拒绝。 */
@Component
public class WorkAccessLogStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkAccessLogStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void append(UUID viewerPersonId, UUID subjectPersonId, String resourceType,
                       String resourceId, String action, List<String> factors) {
        if (viewerPersonId == null || subjectPersonId == null || viewerPersonId.equals(subjectPersonId)) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO worklog_access_log
                    (tenant_id, viewer_person_id, subject_person_id, resource_type,
                     resource_id, action, decision_factors)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?,
                        CAST(? AS jsonb))
                """, viewerPersonId, subjectPersonId, resourceType, resourceId, action,
                objectMapper.writeValueAsString(factors == null ? List.of() : factors));
    }

    public List<AccessEntry> recent(UUID subjectPersonId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query("""
                SELECT id, viewer_person_id, resource_type, resource_id, action,
                       decision_factors, occurred_at
                  FROM worklog_access_log
                 WHERE subject_person_id = ?
                 ORDER BY occurred_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> new AccessEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("viewer_person_id", UUID.class),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("action"),
                        objectMapper.readValue(rs.getString("decision_factors"),
                                new tools.jackson.core.type.TypeReference<List<String>>() { }),
                        rs.getObject("occurred_at", java.time.OffsetDateTime.class)),
                subjectPersonId, bounded);
    }

    public record AccessEntry(UUID id,
                              UUID viewerPersonId,
                              String resourceType,
                              String resourceId,
                              String action,
                              List<String> factors,
                              java.time.OffsetDateTime occurredAt) {
    }
}
