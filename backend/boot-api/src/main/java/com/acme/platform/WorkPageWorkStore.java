package com.acme.platform;

import com.acme.platform.platform.TenantTransactions;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 个人工作页的协作侧只读查询：任务/行动项计数、项目文件数与近期通知。
 *
 * <p>与 {@link WorkPagePersonStore} 一样自己开启租户事务，tenant_id 由 RLS 上下文提供。
 */
@Component
class WorkPageWorkStore {

    private final JdbcTemplate jdbcTemplate;
    private final TenantTransactions transactions;

    WorkPageWorkStore(JdbcTemplate jdbcTemplate, TenantTransactions transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    record TaskSummary(int open, int dueSoon) {
    }

    record ActivityEntry(String title, String resourceType, boolean read, String occurredAt) {
    }

    TaskSummary taskSummary(UUID personId, LocalDate dueUntil) {
        return transactions.inTenant(context -> jdbcTemplate.queryForObject("""
                        SELECT
                            (SELECT count(*) FROM collab_task
                              WHERE assignee_person_id = ? AND status IN ('todo', 'doing'))
                          + (SELECT count(*) FROM collab_action_item
                              WHERE owner_person_id = ? AND status = 'open') AS open_count,
                            (SELECT count(*) FROM collab_task
                              WHERE assignee_person_id = ? AND status IN ('todo', 'doing')
                                AND due_date IS NOT NULL AND due_date <= ?)
                          + (SELECT count(*) FROM collab_action_item
                              WHERE owner_person_id = ? AND status = 'open'
                                AND due_date IS NOT NULL AND due_date <= ?) AS due_soon
                        """, (rs, rowNum) -> new TaskSummary(rs.getInt("open_count"),
                                rs.getInt("due_soon")),
                personId, personId, personId, dueUntil, personId, dueUntil));
    }

    /** 已授权文件：本人所在项目下的文件引用，按 file_id 去重。 */
    int authorizedFileCount(UUID personId) {
        return transactions.inTenant(context -> jdbcTemplate.queryForObject("""
                        SELECT count(DISTINCT link.file_id)
                          FROM collab_file_link link
                          JOIN collab_project_member member
                            ON member.project_id = link.project_id
                           AND member.person_id = ?
                           AND member.removed_at IS NULL
                         WHERE link.removed_at IS NULL
                        """, Integer.class, personId));
    }

    /** 近期动态只对本人开放，内容来自站内通知。 */
    List<ActivityEntry> recentActivity(UUID personId, int limit) {
        return transactions.inTenant(context -> jdbcTemplate.query("""
                        SELECT title, resource_type, read_at, created_at
                          FROM collab_notification
                         WHERE recipient_person_id = ? AND dismissed_at IS NULL
                         ORDER BY created_at DESC
                         LIMIT ?
                        """, (rs, rowNum) -> new ActivityEntry(
                        rs.getString("title"), rs.getString("resource_type"),
                        rs.getObject("read_at", OffsetDateTime.class) != null,
                        String.valueOf(rs.getObject("created_at", OffsetDateTime.class))),
                personId, limit));
    }
}
