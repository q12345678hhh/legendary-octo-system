package com.acme.platform.ai;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 工具目录的 JDBC 读写。
 *
 * <p>所有方法都假设调用方已处于 {@code TenantTransactions.inTenant(...)} 事务中，
 * tenant_id 由 RLS 上下文提供。
 */
@Component
public class AiToolStore {

    private final JdbcTemplate jdbcTemplate;

    public AiToolStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ToolRow(UUID id, String code, String name, String description,
                          String endpointUrl, String inputSchema, String status, Integer rating,
                          String createdBy, String ratedBy, long version,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private static final String COLUMNS = """
            id, code, name, description, endpoint_url, input_schema::text AS input_schema,
            status, rating, created_by, rated_by, version, created_at, updated_at
            """;

    private static ToolRow tool(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ToolRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getString("endpoint_url"),
                rs.getString("input_schema"), rs.getString("status"),
                rs.getObject("rating", Integer.class), rs.getString("created_by"),
                rs.getString("rated_by"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public List<ToolRow> list() {
        return jdbcTemplate.query("SELECT " + COLUMNS + """
                  FROM ai_tool
                 ORDER BY updated_at DESC, code
                """, (rs, rowNum) -> tool(rs));
    }

    public Optional<ToolRow> find(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM ai_tool WHERE id = ?",
                (rs, rowNum) -> tool(rs), id).stream().findFirst();
    }

    public Optional<ToolRow> findByCode(String code) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM ai_tool WHERE code = ?",
                (rs, rowNum) -> tool(rs), code).stream().findFirst();
    }

    public void insert(String code, String name, String description, String endpointUrl,
                       String inputSchema, String actor) {
        jdbcTemplate.update("""
                INSERT INTO ai_tool
                    (tenant_id, code, name, description, endpoint_url, input_schema, created_by)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?::jsonb, ?)
                """, code, name, description, endpointUrl, inputSchema,
                actor == null ? "" : actor);
    }

    /** 乐观锁更新启停状态；返回 false 表示版本已被并发修改。 */
    public boolean updateStatus(UUID id, String status, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE ai_tool
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                """, status, id, expectedVersion) == 1;
    }

    /** 乐观锁更新评分；rating 为 null 表示清除评分，同时记录评分人。 */
    public boolean updateRating(UUID id, Integer rating, String actor, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE ai_tool
                   SET rating = ?, rated_by = ?, rated_at = now(),
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                """, rating, actor == null ? "" : actor, id, expectedVersion) == 1;
    }
}
