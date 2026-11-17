package com.acme.platform.collaboration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** 协作域的 JDBC 存储；调用方必须处于租户事务内。 */
@Component
public class CollaborationStore {

    private final JdbcTemplate jdbcTemplate;

    public CollaborationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ 频道

    public List<Channel> channels(CollabViewer viewer, int limit) {
        List<Object> args = new ArrayList<>();
        String readable = readableChannel("c", viewer, args);
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query("""
                SELECT c.id, c.org_unit_id, c.scope, c.name, c.topic, c.created_by_person_id,
                       c.created_at, c.archived_at
                  FROM collab_channel c
                 WHERE c.archived_at IS NULL AND %s
                 ORDER BY c.name
                 LIMIT ?
                """.formatted(readable), CHANNEL, args.toArray());
    }

    public Optional<Channel> channel(UUID channelId) {
        return jdbcTemplate.query("""
                        SELECT id, org_unit_id, scope, name, topic, created_by_person_id,
                               created_at, archived_at
                          FROM collab_channel
                         WHERE id = ? AND archived_at IS NULL
                        """, CHANNEL, channelId)
                .stream().findFirst();
    }

    public boolean channelReadable(CollabViewer viewer, UUID channelId) {
        List<Object> args = new ArrayList<>();
        args.add(channelId);
        String readable = readableChannel("c", viewer, args);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM collab_channel c
                 WHERE c.id = ? AND c.archived_at IS NULL AND %s
                """.formatted(readable), Integer.class, args.toArray());
        return count != null && count > 0;
    }

    public UUID insertChannel(UUID orgUnitId, String scope, String name, String topic,
                              UUID createdBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_channel
                    (tenant_id, org_unit_id, scope, name, topic, created_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, orgUnitId, scope, name, topic, createdBy);
    }

    public void archiveChannel(UUID channelId) {
        jdbcTemplate.update("""
                UPDATE collab_channel SET archived_at = now(), updated_at = now()
                 WHERE id = ? AND archived_at IS NULL
                """, channelId);
    }

    public List<Member> members(UUID channelId) {
        return jdbcTemplate.query("""
                SELECT person_id, role, added_at, removed_at
                  FROM collab_channel_member
                 WHERE channel_id = ?
                 ORDER BY removed_at NULLS FIRST, added_at
                """, MEMBER, channelId);
    }

    public boolean activeChannelMember(UUID channelId, UUID personId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM collab_channel_member
                 WHERE channel_id = ? AND person_id = ? AND removed_at IS NULL
                """, Integer.class, channelId, personId);
        return count != null && count > 0;
    }

    public void upsertChannelMember(UUID channelId, UUID personId, String role) {
        jdbcTemplate.update("""
                INSERT INTO collab_channel_member (tenant_id, channel_id, person_id, role)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                ON CONFLICT (channel_id, person_id) DO UPDATE
                   SET role = EXCLUDED.role, removed_at = NULL
                """, channelId, personId, role);
    }

    public void removeChannelMember(UUID channelId, UUID personId) {
        jdbcTemplate.update("""
                UPDATE collab_channel_member SET removed_at = now()
                 WHERE channel_id = ? AND person_id = ? AND removed_at IS NULL
                """, channelId, personId);
    }

    // ------------------------------------------------------------------ 帖子、评论、回执

    public List<Post> posts(UUID channelId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, channel_id, author_person_id, title, body, pinned_at, archived_at,
                       created_at
                  FROM collab_post
                 WHERE channel_id = ? AND archived_at IS NULL
                 ORDER BY pinned_at DESC NULLS LAST, created_at DESC
                 LIMIT ?
                """, POST, channelId, Math.max(1, Math.min(limit, 100)));
    }

    public Optional<Post> post(UUID postId) {
        return jdbcTemplate.query("""
                        SELECT id, channel_id, author_person_id, title, body, pinned_at,
                               archived_at, created_at
                          FROM collab_post
                         WHERE id = ?
                        """, POST, postId)
                .stream().findFirst();
    }

    public UUID insertPost(UUID channelId, UUID authorPersonId, String title, String body) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_post (tenant_id, channel_id, author_person_id, title, body)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, channelId, authorPersonId, title, body);
    }

    public void pinPost(UUID postId) {
        jdbcTemplate.update("""
                UPDATE collab_post SET pinned_at = coalesce(pinned_at, now()), updated_at = now()
                 WHERE id = ?
                """, postId);
    }

    public void archivePost(UUID postId) {
        jdbcTemplate.update("""
                UPDATE collab_post SET archived_at = now(), updated_at = now()
                 WHERE id = ? AND archived_at IS NULL
                """, postId);
    }

    public List<Comment> comments(UUID postId) {
        return jdbcTemplate.query("""
                SELECT id, post_id, author_person_id, body, created_at
                  FROM collab_comment
                 WHERE post_id = ?
                 ORDER BY created_at
                """, COMMENT, postId);
    }

    public UUID insertComment(UUID postId, UUID authorPersonId, String body) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_comment (tenant_id, post_id, author_person_id, body)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                RETURNING id
                """, UUID.class, postId, authorPersonId, body);
    }

    public void insertMention(UUID postId, UUID commentId, UUID personId) {
        jdbcTemplate.update("""
                INSERT INTO collab_mention (tenant_id, post_id, comment_id, mentioned_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                """, postId, commentId, personId);
    }

    public void acknowledge(UUID postId, UUID personId) {
        jdbcTemplate.update("""
                INSERT INTO collab_receipt (tenant_id, post_id, person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                ON CONFLICT (post_id, person_id) DO NOTHING
                """, postId, personId);
    }

    public List<UUID> receipts(UUID postId) {
        return jdbcTemplate.queryForList("""
                SELECT person_id FROM collab_receipt WHERE post_id = ?
                """, UUID.class, postId);
    }

    // ------------------------------------------------------------------ 项目与文件引用

    public List<Project> projects(CollabViewer viewer, int limit) {
        List<Object> args = new ArrayList<>();
        String readable = readableProject("p", viewer, args);
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query("""
                SELECT p.id, p.org_unit_id, p.name, p.description, p.status, p.created_at
                  FROM collab_project p
                 WHERE p.status = 'active' AND %s
                 ORDER BY p.created_at DESC
                 LIMIT ?
                """.formatted(readable), PROJECT, args.toArray());
    }

    public Optional<Project> project(UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT id, org_unit_id, name, description, status, created_at
                          FROM collab_project
                         WHERE id = ? AND status = 'active'
                        """, PROJECT, projectId)
                .stream().findFirst();
    }

    public boolean projectReadable(CollabViewer viewer, UUID projectId) {
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        String readable = readableProject("p", viewer, args);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM collab_project p
                 WHERE p.id = ? AND p.status = 'active' AND %s
                """.formatted(readable), Integer.class, args.toArray());
        return count != null && count > 0;
    }

    public UUID insertProject(UUID orgUnitId, String name, String description) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_project (tenant_id, org_unit_id, name, description)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                RETURNING id
                """, UUID.class, orgUnitId, name, description);
    }

    public void upsertProjectMember(UUID projectId, UUID personId, String role) {
        jdbcTemplate.update("""
                INSERT INTO collab_project_member (tenant_id, project_id, person_id, role)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                ON CONFLICT (project_id, person_id) DO UPDATE
                   SET role = EXCLUDED.role, removed_at = NULL
                """, projectId, personId, role);
    }

    public void removeProjectMember(UUID projectId, UUID personId) {
        jdbcTemplate.update("""
                UPDATE collab_project_member SET removed_at = now()
                 WHERE project_id = ? AND person_id = ? AND removed_at IS NULL
                """, projectId, personId);
    }

    public List<UUID> projectMembers(UUID projectId) {
        return jdbcTemplate.queryForList("""
                SELECT person_id FROM collab_project_member
                 WHERE project_id = ? AND removed_at IS NULL
                """, UUID.class, projectId);
    }

    public List<FileLink> fileLinks(UUID channelId, UUID projectId, int limit) {
        if (channelId == null && projectId == null) {
            return List.of();
        }
        String column = channelId != null ? "channel_id" : "project_id";
        UUID value = channelId != null ? channelId : projectId;
        return jdbcTemplate.query("""
                SELECT id, channel_id, post_id, project_id, file_id, linked_by_person_id, created_at
                  FROM collab_file_link
                 WHERE %s = ? AND removed_at IS NULL
                 ORDER BY created_at DESC
                 LIMIT ?
                """.formatted(column), FILE_LINK, value, Math.max(1, Math.min(limit, 100)));
    }

    public UUID insertFileLink(UUID channelId, UUID postId, UUID projectId, UUID fileId,
                               UUID linkedBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_file_link
                    (tenant_id, channel_id, post_id, project_id, file_id, linked_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, channelId, postId, projectId, fileId, linkedBy);
    }

    public void insertFileVersion(UUID fileId, UUID previousFileId, int versionNo) {
        jdbcTemplate.update("""
                INSERT INTO collab_file_version
                    (tenant_id, file_id, previous_file_id, version_no)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                ON CONFLICT (file_id) DO NOTHING
                """, fileId, previousFileId, versionNo);
    }

    public void insertFileTag(UUID fileId, String tag) {
        jdbcTemplate.update("""
                INSERT INTO collab_file_tag (tenant_id, file_id, tag)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
                ON CONFLICT (file_id, tag) DO NOTHING
                """, fileId, tag);
    }

    public List<String> fileTags(UUID fileId) {
        return jdbcTemplate.queryForList("""
                SELECT tag FROM collab_file_tag WHERE file_id = ? ORDER BY tag
                """, String.class, fileId);
    }

    // ------------------------------------------------------------------ 任务与里程碑

    public List<Task> tasks(CollabViewer viewer, UUID projectId, String status, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("1 = 1");
        where.append(" AND ").append(readableTask("t", viewer, args));
        if (projectId != null) {
            where.append(" AND t.project_id = ?");
            args.add(projectId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND t.status = ?");
            args.add(status);
        }
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query("""
                SELECT t.id, t.project_id, t.org_unit_id, t.milestone_id, t.title, t.description,
                       t.status, t.assignee_person_id, t.due_date, t.created_by_person_id,
                       t.created_at
                  FROM collab_task t
                 WHERE %s
                 ORDER BY t.status, t.due_date NULLS LAST, t.created_at DESC
                 LIMIT ?
                """.formatted(where), TASK, args.toArray());
    }

    public Optional<Task> task(UUID taskId) {
        return jdbcTemplate.query("""
                        SELECT id, project_id, org_unit_id, milestone_id, title, description,
                               status, assignee_person_id, due_date, created_by_person_id,
                               created_at
                          FROM collab_task
                         WHERE id = ?
                        """, TASK, taskId)
                .stream().findFirst();
    }

    public boolean taskReadable(CollabViewer viewer, UUID taskId) {
        Task task = task(taskId).orElse(null);
        if (task == null) {
            return false;
        }
        if (viewer.tenantWide()) {
            return true;
        }
        if (viewer.personId() != null && (viewer.personId().equals(task.assigneePersonId())
                || viewer.personId().equals(task.createdByPersonId()))) {
            return true;
        }
        if (task.projectId() != null && projectReadable(viewer, task.projectId())) {
            return true;
        }
        return task.orgUnitId() != null && viewer.seesUnit(task.orgUnitId());
    }

    public UUID insertTask(UUID projectId, UUID orgUnitId, UUID milestoneId, String title,
                           String description, UUID assigneePersonId, LocalDate dueDate,
                           UUID createdBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_task
                    (tenant_id, project_id, org_unit_id, milestone_id, title, description,
                     assignee_person_id, due_date, created_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, projectId, orgUnitId, milestoneId, title, description,
                assigneePersonId, dueDate, createdBy);
    }

    public void updateTaskStatus(UUID taskId, String status) {
        jdbcTemplate.update("""
                UPDATE collab_task
                   SET status = ?, completed_at = CASE WHEN ? = 'done' THEN now() ELSE NULL END,
                       updated_at = now()
                 WHERE id = ?
                """, status, status, taskId);
    }

    public List<Milestone> milestones(UUID projectId) {
        return jdbcTemplate.query("""
                SELECT id, project_id, name, due_date, status
                  FROM collab_milestone
                 WHERE project_id = ?
                 ORDER BY due_date NULLS LAST, created_at
                """, MILESTONE, projectId);
    }

    public UUID insertMilestone(UUID projectId, String name, LocalDate dueDate) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_milestone (tenant_id, project_id, name, due_date)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
                RETURNING id
                """, UUID.class, projectId, name, dueDate);
    }

    // ------------------------------------------------------------------ 会议、行动项、决定

    public List<Meeting> meetings(CollabViewer viewer, UUID orgUnitId, int limit) {
        List<Object> args = new ArrayList<>();
        String clause = unitClause("m.org_unit_id", viewer, args);
        if (orgUnitId != null) {
            clause = clause + " AND m.org_unit_id = ?";
            args.add(orgUnitId);
        }
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query("""
                SELECT m.id, m.org_unit_id, m.project_id, m.title, m.meeting_date, m.notes,
                       m.created_by_person_id, m.created_at
                  FROM collab_meeting m
                 WHERE %s
                 ORDER BY m.meeting_date DESC, m.created_at DESC
                 LIMIT ?
                """.formatted(clause), MEETING, args.toArray());
    }

    public Optional<Meeting> meeting(UUID meetingId) {
        return jdbcTemplate.query("""
                        SELECT id, org_unit_id, project_id, title, meeting_date, notes,
                               created_by_person_id, created_at
                          FROM collab_meeting
                         WHERE id = ?
                        """, MEETING, meetingId)
                .stream().findFirst();
    }

    public Optional<ActionItem> actionItem(UUID actionItemId) {
        return jdbcTemplate.query("""
                        SELECT id, meeting_id, title, owner_person_id, due_date, status
                          FROM collab_action_item
                         WHERE id = ?
                        """, ACTION_ITEM, actionItemId)
                .stream().findFirst();
    }

    public UUID insertMeeting(UUID orgUnitId, UUID projectId, String title, LocalDate meetingDate,
                              String notes, UUID createdBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_meeting
                    (tenant_id, org_unit_id, project_id, title, meeting_date, notes,
                     created_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, orgUnitId, projectId, title, meetingDate, notes, createdBy);
    }

    public List<ActionItem> actionItems(UUID meetingId) {
        return jdbcTemplate.query("""
                SELECT id, meeting_id, title, owner_person_id, due_date, status
                  FROM collab_action_item
                 WHERE meeting_id = ?
                 ORDER BY status, due_date NULLS LAST, created_at
                """, ACTION_ITEM, meetingId);
    }

    public List<ActionItem> actionItemsForOwner(UUID personId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, meeting_id, title, owner_person_id, due_date, status
                  FROM collab_action_item
                 WHERE owner_person_id = ? AND status = 'open'
                 ORDER BY due_date NULLS LAST, created_at
                 LIMIT ?
                """, ACTION_ITEM, personId, Math.max(1, Math.min(limit, 100)));
    }

    public UUID insertActionItem(UUID meetingId, String title, UUID ownerPersonId,
                                 LocalDate dueDate) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_action_item (tenant_id, meeting_id, title, owner_person_id, due_date)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, meetingId, title, ownerPersonId, dueDate);
    }

    public void completeActionItem(UUID actionItemId) {
        jdbcTemplate.update("""
                UPDATE collab_action_item
                   SET status = 'done', completed_at = now()
                 WHERE id = ? AND status = 'open'
                """, actionItemId);
    }

    public List<Decision> decisions(CollabViewer viewer, UUID orgUnitId, int limit) {
        List<Object> args = new ArrayList<>();
        String clause = unitClause("d.org_unit_id", viewer, args);
        if (orgUnitId != null) {
            clause = clause + " AND d.org_unit_id = ?";
            args.add(orgUnitId);
        }
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query("""
                SELECT d.id, d.org_unit_id, d.meeting_id, d.title, d.body, d.decided_at, d.created_at
                  FROM collab_decision d
                 WHERE %s
                 ORDER BY d.decided_at DESC, d.created_at DESC
                 LIMIT ?
                """.formatted(clause), DECISION, args.toArray());
    }

    public UUID insertDecision(UUID orgUnitId, UUID meetingId, String title, String body,
                               LocalDate decidedAt, UUID createdBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_decision
                    (tenant_id, org_unit_id, meeting_id, title, body, decided_at,
                     created_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, orgUnitId, meetingId, title, body, decidedAt, createdBy);
    }

    // ------------------------------------------------------------------ 知识页

    public List<Page> pages(CollabViewer viewer, UUID orgUnitId, int limit) {
        List<Object> args = new ArrayList<>();
        String clause = unitClause("p.org_unit_id", viewer, args);
        if (orgUnitId != null) {
            clause = clause + " AND p.org_unit_id = ?";
            args.add(orgUnitId);
        }
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query("""
                SELECT p.id, p.org_unit_id, p.parent_id, p.title, p.body, p.created_at, p.updated_at
                  FROM collab_page p
                 WHERE p.archived_at IS NULL AND %s
                 ORDER BY p.updated_at DESC
                 LIMIT ?
                """.formatted(clause), PAGE, args.toArray());
    }

    public Optional<Page> page(UUID pageId) {
        return jdbcTemplate.query("""
                        SELECT id, org_unit_id, parent_id, title, body, created_at, updated_at
                          FROM collab_page
                         WHERE id = ? AND archived_at IS NULL
                        """, PAGE, pageId)
                .stream().findFirst();
    }

    public UUID insertPage(UUID orgUnitId, UUID parentId, String title, String body,
                           UUID createdBy) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_page
                    (tenant_id, org_unit_id, parent_id, title, body, created_by_person_id)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, orgUnitId, parentId, title, body, createdBy);
    }

    public void updatePage(UUID pageId, String title, String body) {
        jdbcTemplate.update("""
                UPDATE collab_page SET title = ?, body = ?, updated_at = now()
                 WHERE id = ? AND archived_at IS NULL
                """, title, body, pageId);
    }

    // ------------------------------------------------------------------ 通知与分享

    public UUID insertNotification(UUID recipientPersonId, String resourceType, UUID resourceId,
                                   String title, String body) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO collab_notification
                    (tenant_id, recipient_person_id, resource_type, resource_id, title, body)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, recipientPersonId, resourceType, resourceId, title, body);
    }

    public UUID insertNotificationIfAbsent(UUID recipientPersonId, String resourceType,
                                           UUID resourceId, String title, String body) {
        return jdbcTemplate.query("""
                INSERT INTO collab_notification
                    (tenant_id, recipient_person_id, resource_type, resource_id, title, body)
                SELECT nullif(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?
                 WHERE NOT EXISTS (
                    SELECT 1 FROM collab_notification
                     WHERE recipient_person_id = ? AND resource_type = ?
                       AND resource_id = ? AND title = ?)
                RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                recipientPersonId, resourceType, resourceId, title, body,
                recipientPersonId, resourceType, resourceId, title).stream().findFirst()
                .orElse(null);
    }

    public List<NotificationRow> notifications(UUID recipientPersonId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, recipient_person_id, resource_type, resource_id, title, body,
                       read_at, created_at
                  FROM collab_notification
                 WHERE recipient_person_id = ? AND dismissed_at IS NULL
                 ORDER BY created_at DESC
                 LIMIT ?
                """, NOTIFICATION, recipientPersonId, Math.max(1, Math.min(limit, 100)));
    }

    public int unreadNotificationCount(UUID recipientPersonId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM collab_notification
                 WHERE recipient_person_id = ? AND read_at IS NULL AND dismissed_at IS NULL
                """, Integer.class, recipientPersonId);
        return count == null ? 0 : count;
    }

    public void markNotificationRead(UUID notificationId, UUID recipientPersonId) {
        jdbcTemplate.update("""
                UPDATE collab_notification SET read_at = coalesce(read_at, now())
                 WHERE id = ? AND recipient_person_id = ?
                """, notificationId, recipientPersonId);
    }

    public int dismissUnreadForChannel(UUID channelId) {
        return jdbcTemplate.update("""
                UPDATE collab_notification SET dismissed_at = now()
                 WHERE dismissed_at IS NULL AND (
                       (resource_type = 'channel' AND resource_id = ?)
                    OR (resource_type = 'post' AND resource_id IN
                        (SELECT id FROM collab_post WHERE channel_id = ?)))
                """, channelId, channelId);
    }

    public void insertShareLink(String resourceType, UUID resourceId, String tokenHash,
                                UUID createdBy, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO collab_share_link
                    (tenant_id, resource_type, resource_id, token_hash, created_by_person_id,
                     expires_at)
                VALUES (nullif(current_setting('app.tenant_id', true), '')::uuid,
                        ?, ?, ?, ?, ?)
                """, resourceType, resourceId, tokenHash, createdBy, expiresAt);
    }

    public Optional<ShareLink> shareLinkByHash(String tokenHash) {
        return jdbcTemplate.query("""
                        SELECT id, resource_type, resource_id, token_hash, created_by_person_id,
                               expires_at, revoked_at, created_at
                          FROM collab_share_link
                         WHERE token_hash = ?
                        """, SHARE_LINK, tokenHash)
                .stream().findFirst();
    }

    public int revokeShareLinksForChannel(UUID channelId) {
        return jdbcTemplate.update("""
                UPDATE collab_share_link SET revoked_at = now()
                 WHERE revoked_at IS NULL
                   AND ((resource_type = 'channel' AND resource_id = ?)
                     OR (resource_type = 'post' AND resource_id IN
                         (SELECT id FROM collab_post WHERE channel_id = ?)))
                """, channelId, channelId);
    }

    public int revokeShareLinksCreatedBy(UUID channelId, UUID personId) {
        return jdbcTemplate.update("""
                UPDATE collab_share_link SET revoked_at = now()
                 WHERE revoked_at IS NULL AND created_by_person_id = ?
                   AND ((resource_type = 'channel' AND resource_id = ?)
                     OR (resource_type = 'post' AND resource_id IN
                         (SELECT id FROM collab_post WHERE channel_id = ?)))
                """, personId, channelId, channelId);
    }

    // ------------------------------------------------------------------ 搜索

    public List<SearchHit> search(CollabViewer viewer, String query, int limit) {
        String pattern = likePattern(query);
        List<Object> args = new ArrayList<>();

        List<Object> channelArgs = new ArrayList<>();
        String channelReadable = readableChannel("c", viewer, channelArgs);
        List<Object> unitArgs = new ArrayList<>();
        String unitClause = unitClause("pg.org_unit_id", viewer, unitArgs);
        List<Object> taskArgs = new ArrayList<>();
        String taskReadable = readableTask("t", viewer, taskArgs);

        args.add(pattern);
        args.add(pattern);
        args.addAll(channelArgs);
        args.add(pattern);
        args.add(pattern);
        args.addAll(unitArgs);
        args.add(pattern);
        args.add(pattern);
        args.addAll(taskArgs);
        args.add(Math.max(1, Math.min(limit, 50)));

        return jdbcTemplate.query("""
                SELECT * FROM (
                    SELECT 'post' AS kind, p.id, p.title, left(p.body, 200) AS snippet
                      FROM collab_post p
                      JOIN collab_channel c ON c.id = p.channel_id
                     WHERE p.archived_at IS NULL
                       AND (p.title ILIKE ? OR p.body ILIKE ?)
                       AND %s
                    UNION ALL
                    SELECT 'page' AS kind, pg.id, pg.title, left(pg.body, 200) AS snippet
                      FROM collab_page pg
                     WHERE pg.archived_at IS NULL
                       AND (pg.title ILIKE ? OR pg.body ILIKE ?)
                       AND %s
                    UNION ALL
                    SELECT 'task' AS kind, t.id, t.title, left(t.description, 200) AS snippet
                      FROM collab_task t
                     WHERE (t.title ILIKE ? OR t.description ILIKE ?)
                       AND %s
                ) hits
                ORDER BY kind, title
                LIMIT ?
                """.formatted(channelReadable, unitClause, taskReadable),
                SEARCH_HIT, args.toArray());
    }

    // ------------------------------------------------------------------ 可见性片段

    private static String readableChannel(String alias, CollabViewer viewer, List<Object> args) {
        StringBuilder clause = new StringBuilder("(");
        boolean hasTerm = false;
        if (viewer.personId() != null) {
            clause.append("EXISTS (SELECT 1 FROM collab_channel_member m")
                    .append(" WHERE m.channel_id = ").append(alias).append(".id")
                    .append(" AND m.person_id = ? AND m.removed_at IS NULL)");
            args.add(viewer.personId());
            hasTerm = true;
        }
        if (viewer.tenantWide()) {
            if (hasTerm) {
                clause.append(" OR ");
            }
            clause.append("true");
            hasTerm = true;
        } else if (!viewer.orgUnitIds().isEmpty()) {
            if (hasTerm) {
                clause.append(" OR ");
            }
            clause.append(alias).append(".scope = 'all' AND ").append(alias)
                    .append(".org_unit_id IN (")
                    .append(placeholders(viewer.orgUnitIds().size())).append(")");
            args.addAll(viewer.orgUnitIds());
            hasTerm = true;
        }
        if (!hasTerm) {
            clause.append("false");
        }
        return clause.append(")").toString();
    }

    private static String readableProject(String alias, CollabViewer viewer, List<Object> args) {
        StringBuilder clause = new StringBuilder("(");
        boolean hasTerm = false;
        if (viewer.personId() != null) {
            clause.append("EXISTS (SELECT 1 FROM collab_project_member pm")
                    .append(" WHERE pm.project_id = ").append(alias).append(".id")
                    .append(" AND pm.person_id = ? AND pm.removed_at IS NULL)");
            args.add(viewer.personId());
            hasTerm = true;
        }
        if (viewer.tenantWide()) {
            if (hasTerm) {
                clause.append(" OR ");
            }
            clause.append("true");
            hasTerm = true;
        } else if (!viewer.orgUnitIds().isEmpty()) {
            if (hasTerm) {
                clause.append(" OR ");
            }
            clause.append(alias).append(".org_unit_id IN (")
                    .append(placeholders(viewer.orgUnitIds().size())).append(")");
            args.addAll(viewer.orgUnitIds());
            hasTerm = true;
        }
        if (!hasTerm) {
            clause.append("false");
        }
        return clause.append(")").toString();
    }

    private static String readableTask(String alias, CollabViewer viewer, List<Object> args) {
        StringBuilder clause = new StringBuilder("(");
        boolean hasTerm = false;
        if (viewer.personId() != null) {
            clause.append(alias).append(".created_by_person_id = ? OR ")
                    .append(alias).append(".assignee_person_id = ?");
            args.add(viewer.personId());
            args.add(viewer.personId());
            hasTerm = true;
        }
        if (viewer.tenantWide()) {
            if (hasTerm) {
                clause.append(" OR ");
            }
            clause.append("true");
            hasTerm = true;
        } else {
            if (!viewer.orgUnitIds().isEmpty()) {
                if (hasTerm) {
                    clause.append(" OR ");
                }
                clause.append(alias).append(".org_unit_id IN (")
                        .append(placeholders(viewer.orgUnitIds().size())).append(")");
                args.addAll(viewer.orgUnitIds());
                hasTerm = true;
            }
            if (viewer.personId() != null) {
                clause.append(" OR EXISTS (SELECT 1 FROM collab_project_member pm2")
                        .append(" WHERE pm2.project_id = ").append(alias).append(".project_id")
                        .append(" AND pm2.person_id = ? AND pm2.removed_at IS NULL)");
                args.add(viewer.personId());
                hasTerm = true;
            }
        }
        if (!hasTerm) {
            clause.append("false");
        }
        return clause.append(")").toString();
    }

    private static String unitClause(String column, CollabViewer viewer, List<Object> args) {
        if (viewer.tenantWide()) {
            return "true";
        }
        if (viewer.orgUnitIds().isEmpty()) {
            return "false";
        }
        args.addAll(viewer.orgUnitIds());
        return column + " IN (" + placeholders(viewer.orgUnitIds().size()) + ")";
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static String likePattern(String query) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    // ------------------------------------------------------------------ 映射

    private static final RowMapper<Channel> CHANNEL = (rs, rowNum) -> new Channel(
            rs.getObject("id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getString("scope"),
            rs.getString("name"),
            rs.getString("topic"),
            rs.getObject("created_by_person_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("archived_at", OffsetDateTime.class));

    private static final RowMapper<Member> MEMBER = (rs, rowNum) -> new Member(
            rs.getObject("person_id", UUID.class),
            rs.getString("role"),
            rs.getObject("added_at", OffsetDateTime.class),
            rs.getObject("removed_at", OffsetDateTime.class));

    private static final RowMapper<Post> POST = (rs, rowNum) -> new Post(
            rs.getObject("id", UUID.class),
            rs.getObject("channel_id", UUID.class),
            rs.getObject("author_person_id", UUID.class),
            rs.getString("title"),
            rs.getString("body"),
            rs.getObject("pinned_at", OffsetDateTime.class),
            rs.getObject("archived_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Comment> COMMENT = (rs, rowNum) -> new Comment(
            rs.getObject("id", UUID.class),
            rs.getObject("post_id", UUID.class),
            rs.getObject("author_person_id", UUID.class),
            rs.getString("body"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Project> PROJECT = (rs, rowNum) -> new Project(
            rs.getObject("id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<FileLink> FILE_LINK = (rs, rowNum) -> new FileLink(
            rs.getObject("id", UUID.class),
            rs.getObject("channel_id", UUID.class),
            rs.getObject("post_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("file_id", UUID.class),
            rs.getObject("linked_by_person_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Task> TASK = (rs, rowNum) -> new Task(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getObject("milestone_id", UUID.class),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getObject("assignee_person_id", UUID.class),
            rs.getObject("due_date", LocalDate.class),
            rs.getObject("created_by_person_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Milestone> MILESTONE = (rs, rowNum) -> new Milestone(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("name"),
            rs.getObject("due_date", LocalDate.class),
            rs.getString("status"));

    private static final RowMapper<Meeting> MEETING = (rs, rowNum) -> new Meeting(
            rs.getObject("id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("title"),
            rs.getObject("meeting_date", LocalDate.class),
            rs.getString("notes"),
            rs.getObject("created_by_person_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<ActionItem> ACTION_ITEM = (rs, rowNum) -> new ActionItem(
            rs.getObject("id", UUID.class),
            rs.getObject("meeting_id", UUID.class),
            rs.getString("title"),
            rs.getObject("owner_person_id", UUID.class),
            rs.getObject("due_date", LocalDate.class),
            rs.getString("status"));

    private static final RowMapper<Decision> DECISION = (rs, rowNum) -> new Decision(
            rs.getObject("id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getObject("meeting_id", UUID.class),
            rs.getString("title"),
            rs.getString("body"),
            rs.getObject("decided_at", LocalDate.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Page> PAGE = (rs, rowNum) -> new Page(
            rs.getObject("id", UUID.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getObject("parent_id", UUID.class),
            rs.getString("title"),
            rs.getString("body"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<NotificationRow> NOTIFICATION = (rs, rowNum) ->
            new NotificationRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("recipient_person_id", UUID.class),
                    rs.getString("resource_type"),
                    rs.getObject("resource_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("body"),
                    rs.getObject("read_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<ShareLink> SHARE_LINK = (rs, rowNum) -> new ShareLink(
            rs.getObject("id", UUID.class),
            rs.getString("resource_type"),
            rs.getObject("resource_id", UUID.class),
            rs.getString("token_hash"),
            rs.getObject("created_by_person_id", UUID.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<SearchHit> SEARCH_HIT = (rs, rowNum) -> new SearchHit(
            rs.getString("kind"),
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("snippet"));

    public record Channel(UUID id, UUID orgUnitId, String scope, String name, String topic,
                          UUID createdByPersonId, OffsetDateTime createdAt,
                          OffsetDateTime archivedAt) {
    }

    public record Member(UUID personId, String role, OffsetDateTime addedAt,
                         OffsetDateTime removedAt) {
    }

    public record Post(UUID id, UUID channelId, UUID authorPersonId, String title, String body,
                       OffsetDateTime pinnedAt, OffsetDateTime archivedAt,
                       OffsetDateTime createdAt) {
    }

    public record Comment(UUID id, UUID postId, UUID authorPersonId, String body,
                          OffsetDateTime createdAt) {
    }

    public record Project(UUID id, UUID orgUnitId, String name, String description, String status,
                          OffsetDateTime createdAt) {
    }

    public record FileLink(UUID id, UUID channelId, UUID postId, UUID projectId, UUID fileId,
                           UUID linkedByPersonId, OffsetDateTime createdAt) {
    }

    public record Task(UUID id, UUID projectId, UUID orgUnitId, UUID milestoneId, String title,
                       String description, String status, UUID assigneePersonId,
                       LocalDate dueDate, UUID createdByPersonId, OffsetDateTime createdAt) {
    }

    public record Milestone(UUID id, UUID projectId, String name, LocalDate dueDate,
                            String status) {
    }

    public record Meeting(UUID id, UUID orgUnitId, UUID projectId, String title,
                          LocalDate meetingDate, String notes, UUID createdByPersonId,
                          OffsetDateTime createdAt) {
    }

    public record ActionItem(UUID id, UUID meetingId, String title, UUID ownerPersonId,
                             LocalDate dueDate, String status) {
    }

    public record Decision(UUID id, UUID orgUnitId, UUID meetingId, String title, String body,
                           LocalDate decidedAt, OffsetDateTime createdAt) {
    }

    public record Page(UUID id, UUID orgUnitId, UUID parentId, String title, String body,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record NotificationRow(UUID id, UUID recipientPersonId, String resourceType,
                                  UUID resourceId, String title, String body,
                                  OffsetDateTime readAt, OffsetDateTime createdAt) {
    }

    public record ShareLink(UUID id, String resourceType, UUID resourceId, String tokenHash,
                            UUID createdByPersonId, OffsetDateTime expiresAt,
                            OffsetDateTime revokedAt, OffsetDateTime createdAt) {
    }

    public record SearchHit(String kind, UUID id, String title, String snippet) {
    }
}
