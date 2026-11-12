package com.acme.platform.authorization;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 季度访问复核的 JDBC 存储。
 *
 * <p>租户复核表受 FORCE RLS 保护；Worker 只能通过非 RLS 的
 * {@code platform_access_review_due} 发现待处理租户，再进入租户事务读写业务表。
 */
@Component
public class AccessReviewStore {

    public record ReviewRow(UUID id, LocalDate periodStart, LocalDate periodEnd, String state,
                            OffsetDateTime dueAt, OffsetDateTime createdAt,
                            OffsetDateTime closedAt) {
    }

    public record ItemRow(UUID id, UUID reviewId, UUID assignmentId, UUID subjectActorId,
                          UUID reviewerActorId, String state, OffsetDateTime decidedAt,
                          String note) {
    }

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRow(
            rs.getObject("id", UUID.class),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getString("state"),
            rs.getObject("due_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("closed_at", OffsetDateTime.class));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, rowNum) -> new ItemRow(
            rs.getObject("id", UUID.class),
            rs.getObject("review_id", UUID.class),
            rs.getObject("assignment_id", UUID.class),
            rs.getObject("subject_actor_id", UUID.class),
            rs.getObject("reviewer_actor_id", UUID.class),
            rs.getString("state"),
            rs.getObject("decided_at", OffsetDateTime.class),
            rs.getString("note"));

    private static final String REVIEW_SELECT = """
            SELECT id, period_start, period_end, state, due_at, created_at, closed_at
              FROM authorization_access_review
            """;

    private static final String ITEM_SELECT = """
            SELECT id, review_id, assignment_id, subject_actor_id, reviewer_actor_id,
                   state, decided_at, note
              FROM authorization_access_review_item
            """;

    private final JdbcTemplate jdbcTemplate;

    public AccessReviewStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 跨租户队列

    /** 认领到期租户并先推进时间，避免处理失败后重复生成。 */
    public List<UUID> claimDueTenants(int batchSize) {
        return jdbcTemplate.query("""
                WITH claimed AS (
                    SELECT tenant_id
                      FROM platform_access_review_due
                     WHERE next_run_at <= now()
                     ORDER BY next_run_at
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                )
                UPDATE platform_access_review_due target
                   SET next_run_at = date_trunc('quarter', now()) + interval '3 months',
                       updated_at = now()
                  FROM claimed
                 WHERE target.tenant_id = claimed.tenant_id
                RETURNING target.tenant_id
                """, (rs, rowNum) -> rs.getObject("tenant_id", UUID.class), batchSize);
    }

    /** 认领需要做逾期升级与自动暂停检查的租户，每小时最多一次。 */
    public List<UUID> claimSweepTenants(int batchSize) {
        return jdbcTemplate.query("""
                WITH claimed AS (
                    SELECT tenant_id
                      FROM platform_access_review_due
                     WHERE updated_at < now() - interval '1 hour'
                     ORDER BY updated_at
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                )
                UPDATE platform_access_review_due target
                   SET updated_at = now()
                  FROM claimed
                 WHERE target.tenant_id = claimed.tenant_id
                RETURNING target.tenant_id
                """, (rs, rowNum) -> rs.getObject("tenant_id", UUID.class), batchSize);
    }

    // ---------------------------------------------------------------- 复核

    public Optional<UUID> insertReviewIfAbsent(UUID tenantId, LocalDate periodStart,
                                               LocalDate periodEnd, OffsetDateTime dueAt) {
        return jdbcTemplate.query("""
                INSERT INTO authorization_access_review
                    (tenant_id, period_start, period_end, due_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, period_start) DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId, periodStart, periodEnd, dueAt).stream().findFirst();
    }

    public Optional<ReviewRow> findReview(UUID reviewId) {
        return jdbcTemplate.query(REVIEW_SELECT + " WHERE id = ?", REVIEW_MAPPER, reviewId)
                .stream().findFirst();
    }

    public Optional<ReviewRow> findReviewByPeriod(LocalDate periodStart) {
        return jdbcTemplate.query(REVIEW_SELECT + " WHERE period_start = ?",
                REVIEW_MAPPER, periodStart).stream().findFirst();
    }

    public List<ReviewRow> listReviews(String state, UUID reviewerActorId, int limit) {
        if (reviewerActorId == null) {
            if (state == null || state.isBlank()) {
                return jdbcTemplate.query(REVIEW_SELECT + " ORDER BY period_start DESC LIMIT ?",
                        REVIEW_MAPPER, limit);
            }
            return jdbcTemplate.query(REVIEW_SELECT + """
                     WHERE state = ?
                     ORDER BY period_start DESC LIMIT ?
                    """, REVIEW_MAPPER, state, limit);
        }
        if (state == null || state.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT DISTINCT r.id, r.period_start, r.period_end, r.state, r.due_at,
                                    r.created_at, r.closed_at
                      FROM authorization_access_review r
                      JOIN authorization_access_review_item i
                        ON i.review_id = r.id AND i.tenant_id = r.tenant_id
                     WHERE i.reviewer_actor_id = ?
                     ORDER BY r.period_start DESC
                     LIMIT ?
                    """, REVIEW_MAPPER, reviewerActorId, limit);
        }
        return jdbcTemplate.query("""
                SELECT DISTINCT r.id, r.period_start, r.period_end, r.state, r.due_at,
                                r.created_at, r.closed_at
                  FROM authorization_access_review r
                  JOIN authorization_access_review_item i
                    ON i.review_id = r.id AND i.tenant_id = r.tenant_id
                 WHERE i.reviewer_actor_id = ? AND r.state = ?
                 ORDER BY r.period_start DESC
                 LIMIT ?
                """, REVIEW_MAPPER, reviewerActorId, state, limit);
    }

    public void insertItemIfAbsent(UUID reviewId, UUID assignmentId, UUID subjectActorId,
                                   UUID reviewerActorId) {
        jdbcTemplate.update("""
                INSERT INTO authorization_access_review_item
                    (tenant_id, review_id, assignment_id, subject_actor_id, reviewer_actor_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?)
                ON CONFLICT (tenant_id, review_id, assignment_id) DO NOTHING
                """, reviewId, assignmentId, subjectActorId, reviewerActorId);
    }

    public List<ItemRow> items(UUID reviewId) {
        return jdbcTemplate.query(ITEM_SELECT + """
                 WHERE review_id = ?
                 ORDER BY created_at
                """, ITEM_MAPPER, reviewId);
    }

    public Optional<ItemRow> findItem(UUID reviewId, UUID itemId) {
        return jdbcTemplate.query(ITEM_SELECT + " WHERE review_id = ? AND id = ?",
                ITEM_MAPPER, reviewId, itemId).stream().findFirst();
    }

    public int decideItem(UUID reviewId, UUID itemId, String state, OffsetDateTime decidedAt,
                          String note) {
        return jdbcTemplate.update("""
                UPDATE authorization_access_review_item
                   SET state = ?, decided_at = ?, note = coalesce(?, note), updated_at = now()
                 WHERE review_id = ? AND id = ? AND state = 'pending'
                """, state, decidedAt, note, reviewId, itemId);
    }

    public int countPendingItems(UUID reviewId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM authorization_access_review_item
                 WHERE review_id = ? AND state = 'pending'
                """, Long.class, reviewId);
        return count == null ? 0 : count.intValue();
    }

    public void closeReview(UUID reviewId, OffsetDateTime closedAt) {
        jdbcTemplate.update("""
                UPDATE authorization_access_review
                   SET state = 'closed', closed_at = coalesce(closed_at, ?), updated_at = now()
                 WHERE id = ? AND state <> 'closed'
                """, closedAt, reviewId);
    }

    /** 到期未完成：open → overdue，返回本次升级的复核 ID。 */
    public List<UUID> markOverdue(OffsetDateTime now) {
        return jdbcTemplate.query("""
                UPDATE authorization_access_review
                   SET state = 'overdue', updated_at = now()
                 WHERE state = 'open' AND due_at <= ?
                RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), now);
    }

    /** 宽限期已过仍未决的条目；调用方负责撤销授权并落审计。 */
    public List<ItemRow> pendingItemsPastGrace(OffsetDateTime cutoff) {
        return jdbcTemplate.query("""
                SELECT i.id, i.review_id, i.assignment_id, i.subject_actor_id,
                       i.reviewer_actor_id, i.state, i.decided_at, i.note
                  FROM authorization_access_review_item i
                  JOIN authorization_access_review r
                    ON r.id = i.review_id AND r.tenant_id = i.tenant_id
                 WHERE i.state = 'pending'
                   AND r.state IN ('open', 'overdue')
                   AND r.due_at <= ?
                 ORDER BY i.created_at
                """, ITEM_MAPPER, cutoff);
    }
}
