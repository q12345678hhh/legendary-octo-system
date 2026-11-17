-- V15 手工工时与周报闭环。
--
-- 设计要点：
--   1. worklog_entry 是可编辑草稿；提交后以 worklog_submission 的 JSON 快照为准，快照不可改删；
--   2. 退回只把日状态置为 returned，员工继续编辑同一批草稿后重新提交，版本号单调递增；
--   3. 周报只能引用「已提交/已批准」的日快照，每个数字通过 worklog_report_source 可回溯；
--   4. 所有表启用并强制 RLS；提交人由服务端从当前主体取，数据库不接受客户端指定。

CREATE TABLE worklog_entry (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenancy_tenant (id),
    person_id        uuid        NOT NULL,
    employment_id    uuid,
    work_date        date        NOT NULL,
    starts_at        timestamptz,
    ends_at          timestamptz,
    duration_minutes integer     NOT NULL,
    project_id       uuid,
    task_id          uuid,
    description      text        NOT NULL DEFAULT '',
    source_type      text        NOT NULL DEFAULT 'manual',
    client_entry_id  text        NOT NULL,
    draft_version    integer     NOT NULL DEFAULT 1,
    deleted_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT worklog_entry_duration_check CHECK (duration_minutes BETWEEN 1 AND 1440),
    CONSTRAINT worklog_entry_source_check CHECK (source_type IN ('manual', 'voice')),
    CONSTRAINT worklog_entry_time_check
        CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT worklog_entry_description_check CHECK (length(description) <= 2000),
    CONSTRAINT worklog_entry_version_check CHECK (draft_version >= 1),
    CONSTRAINT worklog_entry_client_unique UNIQUE (tenant_id, person_id, client_entry_id)
);

COMMENT ON TABLE worklog_entry IS
    '工时草稿：可合并/拆分/删除；提交后以 worklog_submission 快照为准';
COMMENT ON COLUMN worklog_entry.client_entry_id IS
    '客户端生成的稳定标识：离线补录与重试必须复用同一个值，避免重复计时';
COMMENT ON COLUMN worklog_entry.source_type IS
    'Phase 3 只允许 manual；voice 为 Phase 4 语音录入预留，服务端当前拒绝写入';

CREATE INDEX worklog_entry_day_idx
    ON worklog_entry (tenant_id, person_id, work_date)
    WHERE deleted_at IS NULL;
CREATE INDEX worklog_entry_project_idx
    ON worklog_entry (tenant_id, project_id, work_date)
    WHERE deleted_at IS NULL;

ALTER TABLE worklog_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_entry FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_entry_isolation ON worklog_entry
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE worklog_submission (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenancy_tenant (id),
    person_id             uuid        NOT NULL,
    work_date             date        NOT NULL,
    version               integer     NOT NULL,
    status                text        NOT NULL DEFAULT 'submitted',
    snapshot              jsonb       NOT NULL,
    snapshot_hash         text        NOT NULL,
    total_minutes         integer     NOT NULL,
    submitted_at          timestamptz NOT NULL DEFAULT now(),
    submitted_by_person_id uuid       NOT NULL,
    reviewed_at           timestamptz,
    reviewed_by_person_id uuid,
    review_comment        text,
    CONSTRAINT worklog_submission_version_check CHECK (version >= 1),
    CONSTRAINT worklog_submission_status_check
        CHECK (status IN ('submitted', 'approved', 'returned')),
    CONSTRAINT worklog_submission_total_check CHECK (total_minutes BETWEEN 0 AND 1440),
    CONSTRAINT worklog_submission_version_unique UNIQUE (tenant_id, person_id, work_date, version),
    CONSTRAINT worklog_submission_review_check CHECK (
        status = 'submitted'
        OR (reviewed_at IS NOT NULL AND reviewed_by_person_id IS NOT NULL))
);

COMMENT ON TABLE worklog_submission IS
    '日工时快照：提交即冻结；退回只追加新版本，旧快照永久保留';

CREATE INDEX worklog_submission_week_idx
    ON worklog_submission (tenant_id, person_id, work_date DESC, version DESC);

ALTER TABLE worklog_submission ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_submission FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_submission_isolation ON worklog_submission
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION worklog_submission_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'worklog_submission 不允许删除';
    END IF;
    IF NEW.person_id <> OLD.person_id
       OR NEW.work_date <> OLD.work_date
       OR NEW.version <> OLD.version
       OR NEW.snapshot IS DISTINCT FROM OLD.snapshot
       OR NEW.snapshot_hash <> OLD.snapshot_hash
       OR NEW.total_minutes <> OLD.total_minutes THEN
        RAISE EXCEPTION 'worklog_submission 快照与提交元数据不可修改';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER worklog_submission_immutable
    BEFORE UPDATE OR DELETE ON worklog_submission
    FOR EACH ROW EXECUTE FUNCTION worklog_submission_guard();

CREATE TABLE worklog_day (
    tenant_id             uuid        NOT NULL REFERENCES tenancy_tenant (id),
    person_id             uuid        NOT NULL,
    work_date             date        NOT NULL,
    state                 text        NOT NULL DEFAULT 'draft',
    draft_version         integer     NOT NULL DEFAULT 1,
    current_submission_id uuid,
    reminder_sent_at      timestamptz,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, person_id, work_date),
    CONSTRAINT worklog_day_state_check
        CHECK (state IN ('draft', 'submitted', 'approved', 'returned')),
    CONSTRAINT worklog_day_submission_fk FOREIGN KEY (current_submission_id)
        REFERENCES worklog_submission (id)
);

COMMENT ON TABLE worklog_day IS
    '按人按日的汇总与状态；版本号用于提交幂等与「退回后新版本」';

ALTER TABLE worklog_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_day FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_day_isolation ON worklog_day
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE worklog_weekly_report (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenancy_tenant (id),
    person_id            uuid        NOT NULL,
    week_start           date        NOT NULL,
    week_end             date        NOT NULL,
    version              integer     NOT NULL DEFAULT 1,
    status               text        NOT NULL DEFAULT 'draft',
    content              jsonb       NOT NULL,
    snapshot_hash        text,
    generated_at         timestamptz NOT NULL DEFAULT now(),
    submitted_at         timestamptz,
    reviewed_at          timestamptz,
    reviewed_by_person_id uuid,
    review_comment       text,
    CONSTRAINT worklog_weekly_report_week_check
        CHECK (week_end = week_start + 6 AND extract(isodow FROM week_start) = 1),
    CONSTRAINT worklog_weekly_report_status_check
        CHECK (status IN ('draft', 'submitted', 'approved', 'returned')),
    CONSTRAINT worklog_weekly_report_version_unique
        UNIQUE (tenant_id, person_id, week_start, version),
    CONSTRAINT worklog_weekly_report_review_check CHECK (
        status IN ('draft', 'submitted')
        OR (reviewed_at IS NOT NULL AND reviewed_by_person_id IS NOT NULL))
);

COMMENT ON TABLE worklog_weekly_report IS
    '周报：draft 可编辑；提交后 content 与来源映射冻结，退回追加新版本';

CREATE INDEX worklog_weekly_report_week_idx
    ON worklog_weekly_report (tenant_id, person_id, week_start DESC, version DESC);

ALTER TABLE worklog_weekly_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_weekly_report FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_weekly_report_isolation ON worklog_weekly_report
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION worklog_report_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'draft' THEN
            RAISE EXCEPTION '已提交的周报不允许删除';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status <> 'draft'
       AND (NEW.content IS DISTINCT FROM OLD.content
            OR NEW.person_id <> OLD.person_id
            OR NEW.week_start <> OLD.week_start
            OR NEW.version <> OLD.version) THEN
        RAISE EXCEPTION '已提交周报的内容与元数据不可修改';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER worklog_weekly_report_immutable
    BEFORE UPDATE OR DELETE ON worklog_weekly_report
    FOR EACH ROW EXECUTE FUNCTION worklog_report_guard();

CREATE TABLE worklog_report_source (
    tenant_id     uuid NOT NULL REFERENCES tenancy_tenant (id),
    report_id     uuid NOT NULL REFERENCES worklog_weekly_report (id) ON DELETE CASCADE,
    submission_id uuid NOT NULL REFERENCES worklog_submission (id),
    item_key      text NOT NULL,
    minutes       integer NOT NULL,
    PRIMARY KEY (report_id, submission_id, item_key),
    CONSTRAINT worklog_report_source_minutes_check CHECK (minutes >= 0)
);

COMMENT ON TABLE worklog_report_source IS
    '周报来源映射：每个汇总数字都能回溯到具体日快照';

CREATE INDEX worklog_report_source_tenant_idx
    ON worklog_report_source (tenant_id, report_id);

ALTER TABLE worklog_report_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_report_source FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_report_source_isolation ON worklog_report_source
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE worklog_setting (
    tenant_id          uuid PRIMARY KEY REFERENCES tenancy_tenant (id),
    min_group_size     integer NOT NULL DEFAULT 5,
    max_daily_minutes  integer NOT NULL DEFAULT 1440,
    updated_by         text NOT NULL DEFAULT 'system',
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT worklog_setting_group_check CHECK (min_group_size BETWEEN 5 AND 100),
    CONSTRAINT worklog_setting_daily_check CHECK (max_daily_minutes BETWEEN 60 AND 1440)
);

COMMENT ON TABLE worklog_setting IS
    '租户级工时设置：洞察最小群组只能从 5 往上调，不能放宽到 5 以下';

ALTER TABLE worklog_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_setting FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_setting_isolation ON worklog_setting
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- 回收站与留置扩展到工时草稿/周报
ALTER TABLE retention_bin_item DROP CONSTRAINT retention_bin_item_class_check;
ALTER TABLE retention_bin_item ADD CONSTRAINT retention_bin_item_class_check
    CHECK (data_class IN ('file', 'work_assistant_segment', 'worklog_entry'));

ALTER TABLE retention_legal_hold DROP CONSTRAINT retention_legal_hold_class_check;
ALTER TABLE retention_legal_hold ADD CONSTRAINT retention_legal_hold_class_check
    CHECK (data_class IN ('file', 'work_assistant_segment', 'worklog_entry',
                          'worklog_weekly_report', 'person', 'payroll_result', 'audit'));
