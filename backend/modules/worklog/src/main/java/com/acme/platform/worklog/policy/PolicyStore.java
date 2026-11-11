package com.acme.platform.worklog.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户策略追加项存储。
 *
 * <p>只存「追加的排除进程」；平台下限不出现在这张表里，也就不存在被删减的入口。
 */
@Component
public class PolicyStore {

    private final JdbcTemplate jdbcTemplate;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public PolicyStore(JdbcTemplate jdbcTemplate, tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<TenantPolicy> find(UUID tenantId) {
        return query(tenantId, false);
    }

    /** 更新前加行锁，避免两个管理员并发追加时丢版本或丢排除项。 */
    public Optional<TenantPolicy> lock(UUID tenantId) {
        return query(tenantId, true);
    }

    public void insert(UUID tenantId, int version, List<String> exclusions, String updatedBy) {
        jdbcTemplate.update("""
                INSERT INTO work_assistant_policy
                    (tenant_id, version, additional_exclusions, updated_by)
                VALUES (?, ?, ?::jsonb, ?)
                """, tenantId, version, json(exclusions), updatedBy);
    }

    public void update(UUID tenantId, int version, List<String> exclusions, String updatedBy) {
        jdbcTemplate.update("""
                UPDATE work_assistant_policy
                   SET version = ?, additional_exclusions = ?::jsonb, updated_at = now(), updated_by = ?
                 WHERE tenant_id = ?
                """, version, json(exclusions), updatedBy, tenantId);
    }

    private Optional<TenantPolicy> query(UUID tenantId, boolean forUpdate) {
        String sql = "SELECT version, additional_exclusions::text AS exclusions"
                + " FROM work_assistant_policy WHERE tenant_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TenantPolicy(
                        rs.getInt("version"),
                        objectMapper.readValue(rs.getString("exclusions"),
                                new tools.jackson.core.type.TypeReference<List<String>>() {
                                })),
                tenantId)
                .stream().findFirst();
    }

    private String json(List<String> exclusions) {
        return objectMapper.writeValueAsString(exclusions);
    }

    /** 租户追加项快照。 */
    public record TenantPolicy(int version, List<String> exclusions) {
    }
}
