-- V16 部门协作最小原语。
--
-- 设计要点：
--   1. 频道成员是协作可见性的唯一来源；`all` 频道对部门内成员可见，但不会放大文件/工时等其它域的 ACL；
--   2. 文件链接只增加引用，不改变 platform_file 的下载授权；移除成员后旧链接与分享链接立即失效；
--   3. 搜索在 Phase 3 采用查询时权限过滤（联查成员表），不引入外部索引；
--   4. 所有表启用并强制 RLS，每表恰好一条隔离策略。

CREATE TABLE collab_channel (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id          uuid        NOT NULL,
    scope                text        NOT NULL DEFAULT 'members',
    name                 text        NOT NULL,
    topic                text        NOT NULL DEFAULT '',
    created_by_person_id uuid        NOT NULL,
    archived_at          timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_channel_scope_check
        CHECK (scope IN ('all', 'members', 'cross_department')),
    CONSTRAINT collab_channel_name_check CHECK (length(name) BETWEEN 1 AND 120),
    CONSTRAINT collab_channel_topic_check CHECK (length(topic) <= 1000),
    CONSTRAINT collab_channel_name_unique UNIQUE (tenant_id, org_unit_id, name)
);

COMMENT ON TABLE collab_channel IS
    '部门频道：all 频道由部门空间创建，members 频道仅成员可见，cross_department 需显式加人';

CREATE INDEX collab_channel_unit_idx
    ON collab_channel (tenant_id, org_unit_id) WHERE archived_at IS NULL;

ALTER TABLE collab_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_channel FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_channel_isolation ON collab_channel
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_channel_member (
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    channel_id  uuid        NOT NULL REFERENCES collab_channel (id) ON DELETE CASCADE,
    person_id   uuid        NOT NULL,
    role        text        NOT NULL DEFAULT 'member',
    added_at    timestamptz NOT NULL DEFAULT now(),
    removed_at  timestamptz,
    PRIMARY KEY (channel_id, person_id),
    CONSTRAINT collab_channel_member_role_check CHECK (role IN ('owner', 'member'))
);

COMMENT ON TABLE collab_channel_member IS
    '频道成员：移除立即写 removed_at，旧链接/搜索/通知在下一请求即失效';

CREATE INDEX collab_channel_member_person_idx
    ON collab_channel_member (tenant_id, person_id) WHERE removed_at IS NULL;

ALTER TABLE collab_channel_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_channel_member FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_channel_member_isolation ON collab_channel_member
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_post (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    channel_id       uuid        NOT NULL REFERENCES collab_channel (id) ON DELETE CASCADE,
    author_person_id uuid        NOT NULL,
    title            text        NOT NULL,
    body             text        NOT NULL DEFAULT '',
    pinned_at        timestamptz,
    archived_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_post_title_check CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT collab_post_body_check CHECK (length(body) <= 20000)
);

CREATE INDEX collab_post_channel_idx
    ON collab_post (tenant_id, channel_id, created_at DESC) WHERE archived_at IS NULL;

ALTER TABLE collab_post ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_post FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_post_isolation ON collab_post
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_comment (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    post_id          uuid        NOT NULL REFERENCES collab_post (id) ON DELETE CASCADE,
    author_person_id uuid        NOT NULL,
    body             text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_comment_body_check CHECK (length(body) BETWEEN 1 AND 5000)
);

CREATE INDEX collab_comment_post_idx
    ON collab_comment (tenant_id, post_id, created_at);

ALTER TABLE collab_comment ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_comment FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_comment_isolation ON collab_comment
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_mention (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    post_id             uuid        NOT NULL REFERENCES collab_post (id) ON DELETE CASCADE,
    comment_id          uuid        REFERENCES collab_comment (id) ON DELETE CASCADE,
    mentioned_person_id uuid        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX collab_mention_person_idx
    ON collab_mention (tenant_id, mentioned_person_id, created_at DESC);

ALTER TABLE collab_mention ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_mention FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_mention_isolation ON collab_mention
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_receipt (
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    post_id       uuid        NOT NULL REFERENCES collab_post (id) ON DELETE CASCADE,
    person_id     uuid        NOT NULL,
    acknowledged_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, person_id)
);

ALTER TABLE collab_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_receipt FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_receipt_isolation ON collab_receipt
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_project (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id  uuid        NOT NULL,
    name         text        NOT NULL,
    description  text        NOT NULL DEFAULT '',
    status       text        NOT NULL DEFAULT 'active',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_project_status_check CHECK (status IN ('active', 'archived')),
    CONSTRAINT collab_project_name_check CHECK (length(name) BETWEEN 1 AND 120),
    CONSTRAINT collab_project_description_check CHECK (length(description) <= 5000),
    CONSTRAINT collab_project_name_unique UNIQUE (tenant_id, org_unit_id, name)
);

CREATE INDEX collab_project_unit_idx
    ON collab_project (tenant_id, org_unit_id) WHERE status = 'active';

ALTER TABLE collab_project ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_project FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_project_isolation ON collab_project
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_project_member (
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    project_id uuid        NOT NULL REFERENCES collab_project (id) ON DELETE CASCADE,
    person_id  uuid        NOT NULL,
    role       text        NOT NULL DEFAULT 'member',
    added_at   timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    PRIMARY KEY (project_id, person_id),
    CONSTRAINT collab_project_member_role_check CHECK (role IN ('owner', 'member'))
);

CREATE INDEX collab_project_member_person_idx
    ON collab_project_member (tenant_id, person_id) WHERE removed_at IS NULL;

ALTER TABLE collab_project_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_project_member FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_project_member_isolation ON collab_project_member
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_file_link (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    channel_id          uuid        REFERENCES collab_channel (id) ON DELETE CASCADE,
    post_id             uuid        REFERENCES collab_post (id) ON DELETE CASCADE,
    project_id          uuid        REFERENCES collab_project (id) ON DELETE CASCADE,
    file_id             uuid        NOT NULL REFERENCES platform_file (id),
    linked_by_person_id uuid        NOT NULL,
    removed_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_file_link_target_check
        CHECK (channel_id IS NOT NULL OR post_id IS NOT NULL OR project_id IS NOT NULL)
);

COMMENT ON TABLE collab_file_link IS
    '文件引用：只增加引用，不改变 platform_file 的下载 ACL；成员移除后按引用失效';

CREATE INDEX collab_file_link_channel_idx
    ON collab_file_link (tenant_id, channel_id) WHERE removed_at IS NULL;
CREATE INDEX collab_file_link_project_idx
    ON collab_file_link (tenant_id, project_id) WHERE removed_at IS NULL;

ALTER TABLE collab_file_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_file_link FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_file_link_isolation ON collab_file_link
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_file_version (
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    file_id          uuid        NOT NULL REFERENCES platform_file (id),
    previous_file_id uuid        REFERENCES platform_file (id),
    version_no       integer     NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (file_id),
    CONSTRAINT collab_file_version_no_check CHECK (version_no >= 1)
);

ALTER TABLE collab_file_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_file_version FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_file_version_isolation ON collab_file_version
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_file_tag (
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    file_id    uuid        NOT NULL REFERENCES platform_file (id),
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (file_id, tag),
    CONSTRAINT collab_file_tag_check CHECK (length(tag) BETWEEN 1 AND 40)
);

ALTER TABLE collab_file_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_file_tag FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_file_tag_isolation ON collab_file_tag
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_milestone (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenancy_tenant (id),
    project_id uuid        NOT NULL REFERENCES collab_project (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    due_date   date,
    status     text        NOT NULL DEFAULT 'planned',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_milestone_status_check CHECK (status IN ('planned', 'achieved', 'missed')),
    CONSTRAINT collab_milestone_name_check CHECK (length(name) BETWEEN 1 AND 120)
);

ALTER TABLE collab_milestone ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_milestone FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_milestone_isolation ON collab_milestone
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_task (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenancy_tenant (id),
    project_id         uuid        REFERENCES collab_project (id) ON DELETE CASCADE,
    org_unit_id        uuid,
    milestone_id       uuid        REFERENCES collab_milestone (id) ON DELETE SET NULL,
    title              text        NOT NULL,
    description        text        NOT NULL DEFAULT '',
    status             text        NOT NULL DEFAULT 'todo',
    assignee_person_id uuid,
    due_date           date,
    created_by_person_id uuid      NOT NULL,
    completed_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_task_status_check CHECK (status IN ('todo', 'doing', 'done')),
    CONSTRAINT collab_task_title_check CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT collab_task_description_check CHECK (length(description) <= 5000),
    CONSTRAINT collab_task_scope_check
        CHECK (project_id IS NOT NULL OR org_unit_id IS NOT NULL)
);

CREATE INDEX collab_task_project_idx
    ON collab_task (tenant_id, project_id, status, due_date);
CREATE INDEX collab_task_assignee_idx
    ON collab_task (tenant_id, assignee_person_id, status);

ALTER TABLE collab_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_task FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_task_isolation ON collab_task
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_meeting (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id      uuid        NOT NULL,
    project_id       uuid        REFERENCES collab_project (id) ON DELETE SET NULL,
    title            text        NOT NULL,
    meeting_date     date        NOT NULL,
    notes            text        NOT NULL DEFAULT '',
    created_by_person_id uuid    NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_meeting_title_check CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT collab_meeting_notes_check CHECK (length(notes) <= 20000)
);

CREATE INDEX collab_meeting_unit_idx
    ON collab_meeting (tenant_id, org_unit_id, meeting_date DESC);

ALTER TABLE collab_meeting ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_meeting FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_meeting_isolation ON collab_meeting
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_action_item (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    meeting_id       uuid        REFERENCES collab_meeting (id) ON DELETE CASCADE,
    title            text        NOT NULL,
    owner_person_id  uuid        NOT NULL,
    due_date         date,
    status           text        NOT NULL DEFAULT 'open',
    created_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    CONSTRAINT collab_action_item_status_check CHECK (status IN ('open', 'done')),
    CONSTRAINT collab_action_item_title_check CHECK (length(title) BETWEEN 1 AND 200)
);

CREATE INDEX collab_action_item_owner_idx
    ON collab_action_item (tenant_id, owner_person_id, status);

ALTER TABLE collab_action_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_action_item FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_action_item_isolation ON collab_action_item
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_decision (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id      uuid        NOT NULL,
    meeting_id       uuid        REFERENCES collab_meeting (id) ON DELETE SET NULL,
    title            text        NOT NULL,
    body             text        NOT NULL DEFAULT '',
    decided_at       date        NOT NULL,
    created_by_person_id uuid    NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_decision_title_check CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT collab_decision_body_check CHECK (length(body) <= 20000)
);

CREATE INDEX collab_decision_unit_idx
    ON collab_decision (tenant_id, org_unit_id, decided_at DESC);

ALTER TABLE collab_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_decision FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_decision_isolation ON collab_decision
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_page (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id      uuid        NOT NULL,
    parent_id        uuid        REFERENCES collab_page (id) ON DELETE RESTRICT,
    title            text        NOT NULL,
    body             text        NOT NULL DEFAULT '',
    created_by_person_id uuid    NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    archived_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_page_title_check CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT collab_page_body_check CHECK (length(body) <= 50000)
);

CREATE INDEX collab_page_unit_idx
    ON collab_page (tenant_id, org_unit_id, parent_id) WHERE archived_at IS NULL;

ALTER TABLE collab_page ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_page FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_page_isolation ON collab_page
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_notification (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    recipient_person_id uuid        NOT NULL,
    resource_type       text        NOT NULL,
    resource_id         uuid        NOT NULL,
    title               text        NOT NULL,
    body                text        NOT NULL DEFAULT '',
    read_at             timestamptz,
    dismissed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_notification_resource_check
        CHECK (resource_type IN ('channel', 'post', 'comment', 'task', 'meeting', 'page')),
    CONSTRAINT collab_notification_title_check CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT collab_notification_body_check CHECK (length(body) <= 2000)
);

COMMENT ON TABLE collab_notification IS
    '站内通知：写库前必须确认收件人对目标仍有读权限；撤权时清理未读';

CREATE INDEX collab_notification_recipient_idx
    ON collab_notification (tenant_id, recipient_person_id, created_at DESC)
    WHERE dismissed_at IS NULL;

ALTER TABLE collab_notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_notification FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_notification_isolation ON collab_notification
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE collab_share_link (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenancy_tenant (id),
    resource_type       text        NOT NULL,
    resource_id         uuid        NOT NULL,
    token_hash          text        NOT NULL,
    created_by_person_id uuid       NOT NULL,
    expires_at          timestamptz,
    revoked_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collab_share_link_resource_check
        CHECK (resource_type IN ('channel', 'post', 'page')),
    CONSTRAINT collab_share_link_token_unique UNIQUE (tenant_id, token_hash)
);

COMMENT ON TABLE collab_share_link IS
    '直接分享链接：成员移除或资源归档时立即写 revoked_at，解析端点重新鉴权';

CREATE INDEX collab_share_link_resource_idx
    ON collab_share_link (tenant_id, resource_type, resource_id) WHERE revoked_at IS NULL;

ALTER TABLE collab_share_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE collab_share_link FORCE ROW LEVEL SECURITY;

CREATE POLICY collab_share_link_isolation ON collab_share_link
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
