package com.acme.platform.platform.idempotency;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等记录存储。所有方法都要求调用方已通过 TenantTransactions 绑定租户上下文。
 */
@Component
public class IdempotencyStore {

    private static final String INSERT_SQL = """
            INSERT INTO platform_idempotency
                (id, tenant_id, subject_id, endpoint, idempotency_key, request_hash, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT platform_idempotency_key_unique DO NOTHING
            """;

    private static final String FIND_SQL = """
            SELECT id, request_hash, status, response_status, response_content_type,
                   response_body, expires_at
              FROM platform_idempotency
             WHERE subject_id = ? AND endpoint = ? AND idempotency_key = ?
            """;

    private static final String COMPLETE_SQL = """
            UPDATE platform_idempotency
               SET status = 'completed',
                   response_status = ?,
                   response_content_type = ?,
                   response_body = CAST(? AS jsonb),
                   completed_at = now()
             WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 返回 true 表示本次抢到了执行权；false 表示已存在同键记录。 */
    public boolean tryInsert(UUID id, UUID tenantId, String subjectId, String endpoint,
                             String key, String requestHash, Duration ttl) {
        int inserted = jdbcTemplate.update(INSERT_SQL,
                id, tenantId, subjectId, endpoint, key, requestHash,
                OffsetDateTime.now().plus(ttl));
        return inserted == 1;
    }

    public Optional<IdempotencyRecord> find(String subjectId, String endpoint, String key) {
        return jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> new IdempotencyRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_content_type"),
                        rs.getString("response_body"),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                subjectId, endpoint, key).stream().findFirst();
    }

    public void complete(UUID id, int status, String contentType, String body) {
        // response_body 是 jsonb：只缓存 JSON 响应，非 JSON 一律留空，避免非法 JSON 让 UPDATE 失败
        String json = contentType != null && contentType.contains("json") ? body : null;
        jdbcTemplate.update(COMPLETE_SQL, status, contentType, json, id);
    }

    /** 业务失败（5xx/异常）时删除占位，允许调用方换一个幂等键或原样重试。 */
    public void release(UUID id) {
        jdbcTemplate.update("DELETE FROM platform_idempotency WHERE id = ?", id);
    }
}
