-- WP02-08：回收站、法定留置、保留策略与数据主体请求。

-- ------------------------------------------------------------------ 回收站（租户域）

CREATE TABLE retention_bin_item (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenancy_tenant (id),
    data_class    text        NOT NULL,
    object_id     uuid        NOT NULL,
    object_ref    text,
    snapshot      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status        text        NOT NULL DEFAULT 'active',
    deleted_by    text        NOT NULL,
    deleted_at    timestamptz NOT NULL DEFAULT now(),
    purge_after   timestamptz NOT NULL,
    restored_at   timestamptz,
    restored_by   text,
    purged_at     timestamptz,
    anonymized_at timestamptz,
    CONSTRAINT retention_bin_item_class_check
        CHECK (data_class IN ('file', 'work_assistant_segment')),
    CONSTRAINT retention_bin_item_status_check
        CHECK (status IN ('active', 'restored', 'purged', 'anonymized')),
    CONSTRAINT retention_bin_item_purge_after_check CHECK (purge_after > deleted_at),
    CONSTRAINT retention_bin_item_snapshot_check CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT retention_bin_item_restore_check
        CHECK (status <> 'restored' OR (restored_at IS NOT NULL AND restored_by IS NOT NULL)),
    CONSTRAINT retention_bin_item_purge_check
        CHECK (status <> 'purged' OR purged_at IS NOT NULL),
    CONSTRAINT retention_bin_item_anonymize_check
        CHECK (status <> 'anonymized' OR anonymized_at IS NOT NULL)
);

COMMENT ON TABLE retention_bin_item IS
    '回收站：只存删除时刻的对象元数据快照，不存业务正文；到期由 Worker 先写墓碑再物理清理';

CREATE UNIQUE INDEX retention_bin_item_active_unique
    ON retention_bin_item (tenant_id, data_class, object_id) WHERE status = 'active';
CREATE INDEX retention_bin_item_due_idx
    ON retention_bin_item (tenant_id, purge_after) WHERE status = 'active';
CREATE INDEX retention_bin_item_tenant_idx
    ON retention_bin_item (tenant_id, deleted_at DESC);

ALTER TABLE retention_bin_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_bin_item FORCE ROW LEVEL SECURITY;

CREATE POLICY retention_bin_item_isolation ON retention_bin_item
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 法定留置

CREATE TABLE retention_legal_hold (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    data_class  text        NOT NULL,
    object_id   uuid,
    reason      text        NOT NULL,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    released_by text,
    CONSTRAINT retention_legal_hold_class_check
        CHECK (data_class IN ('file', 'work_assistant_segment', 'person', 'payroll_result', 'audit')),
    CONSTRAINT retention_legal_hold_reason_check
        CHECK (length(btrim(reason)) BETWEEN 4 AND 500),
    CONSTRAINT retention_legal_hold_release_check
        CHECK (released_at IS NULL OR released_by IS NOT NULL)
);

COMMENT ON TABLE retention_legal_hold IS
    '法定留置：object_id 为空表示整类留置；有生效留置时普通删除必须返回明确原因';

CREATE INDEX retention_legal_hold_active_idx
    ON retention_legal_hold (tenant_id, data_class, object_id) WHERE released_at IS NULL;

ALTER TABLE retention_legal_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_legal_hold FORCE ROW LEVEL SECURITY;

CREATE POLICY retention_legal_hold_isolation ON retention_legal_hold
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 租户侧留痕（只追加）

CREATE TABLE retention_event (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    action      text        NOT NULL,
    data_class  text,
    object_id   uuid,
    actor       text        NOT NULL,
    detail      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT retention_event_action_check
        CHECK (action IN ('recycled', 'restored', 'purged', 'anonymized',
                          'hold_created', 'hold_released',
                          'request_submitted', 'request_decided', 'request_executed')),
    CONSTRAINT retention_event_detail_check CHECK (jsonb_typeof(detail) = 'object')
);

COMMENT ON TABLE retention_event IS
    '删除/保留/数据主体请求的租户侧留痕：只追加，不修改不删除；平台侧动作写 platform_audit_event';

CREATE INDEX retention_event_tenant_idx ON retention_event (tenant_id, occurred_at DESC);

CREATE FUNCTION retention_event_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'retention_event 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER retention_event_immutable_trigger
    BEFORE UPDATE OR DELETE ON retention_event
    FOR EACH ROW EXECUTE FUNCTION retention_event_immutable();

ALTER TABLE retention_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_event FORCE ROW LEVEL SECURITY;

CREATE POLICY retention_event_isolation ON retention_event
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 数据主体请求（平台表，无 RLS）

CREATE TABLE platform_deletion_request (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL,
    subject_type  text        NOT NULL DEFAULT 'person',
    subject_id    uuid        NOT NULL,
    subject_hash  text        NOT NULL,
    reason        text        NOT NULL,
    status        text        NOT NULL DEFAULT 'received',
    decision_code text,
    decision_note text,
    requested_by  text        NOT NULL,
    requested_at  timestamptz NOT NULL DEFAULT now(),
    decided_by    text,
    decided_at    timestamptz,
    executed_by   text,
    executed_at   timestamptz,
    propagated_at timestamptz,
    CONSTRAINT platform_deletion_request_subject_check
        CHECK (subject_type IN ('person', 'account')),
    CONSTRAINT platform_deletion_request_status_check
        CHECK (status IN ('received', 'verifying', 'approved', 'rejected',
                          'executing', 'completed', 'failed', 'cancelled')),
    CONSTRAINT platform_deletion_request_reason_check
        CHECK (length(btrim(reason)) BETWEEN 4 AND 500),
    CONSTRAINT platform_deletion_request_subject_hash_check
        CHECK (subject_hash ~ '^[0-9a-f]{16,64}$'),
    CONSTRAINT platform_deletion_request_decision_check
        CHECK (status NOT IN ('approved', 'rejected') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);

COMMENT ON TABLE platform_deletion_request IS
    '数据主体删除请求案件：只存主体哈希与判定结论，不存姓名、邮箱等个人信息正文';

CREATE INDEX platform_deletion_request_status_idx
    ON platform_deletion_request (status, requested_at DESC);
CREATE INDEX platform_deletion_request_tenant_idx
    ON platform_deletion_request (tenant_id, requested_at DESC);

CREATE TABLE platform_deletion_tombstone (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL,
    data_class   text        NOT NULL,
    object_id    uuid,
    subject_hash text,
    action       text        NOT NULL,
    request_id   uuid REFERENCES platform_deletion_request (id),
    detail       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_deletion_tombstone_action_check
        CHECK (action IN ('anonymized', 'purged')),
    CONSTRAINT platform_deletion_tombstone_target_check
        CHECK (object_id IS NOT NULL OR subject_hash IS NOT NULL)
);

COMMENT ON TABLE platform_deletion_tombstone IS
    '删除墓碑：备份不可变，恢复后按墓碑重放，避免已删除内容复活';

CREATE INDEX platform_deletion_tombstone_tenant_idx
    ON platform_deletion_tombstone (tenant_id, data_class);

CREATE FUNCTION platform_deletion_tombstone_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'platform_deletion_tombstone 只允许插入，不允许修改或删除';
END
$$;

CREATE TRIGGER platform_deletion_tombstone_immutable_trigger
    BEFORE UPDATE OR DELETE ON platform_deletion_tombstone
    FOR EACH ROW EXECUTE FUNCTION platform_deletion_tombstone_immutable();

CREATE TABLE platform_tombstone_replay (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tombstone_id uuid        NOT NULL REFERENCES platform_deletion_tombstone (id),
    tenant_id    uuid        NOT NULL,
    outcome      text        NOT NULL,
    detail       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    replayed_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_tombstone_replay_outcome_check
        CHECK (outcome IN ('applied', 'skipped', 'failed'))
);

COMMENT ON TABLE platform_tombstone_replay IS
    '墓碑重放记录：每次恢复后重放都要留一条，可重复执行且结果可核对';

CREATE INDEX platform_tombstone_replay_tombstone_idx
    ON platform_tombstone_replay (tombstone_id, replayed_at DESC);

-- ------------------------------------------------------------------ 既有表调整

ALTER TABLE platform_file DROP CONSTRAINT platform_file_status_check;
ALTER TABLE platform_file ADD CONSTRAINT platform_file_status_check
    CHECK (status IN ('pending_upload', 'uploaded', 'available', 'rejected', 'recycled'));

ALTER TABLE work_assistant_segment ADD COLUMN deleted_at timestamptz;

ALTER TABLE platform_setting DROP CONSTRAINT platform_setting_category_check;
ALTER TABLE platform_setting ADD CONSTRAINT platform_setting_category_check
    CHECK (category IN ('email', 'storage', 'model', 'limits', 'retention'));

ALTER TABLE platform_setting DROP CONSTRAINT platform_setting_key_check;
ALTER TABLE platform_setting ADD CONSTRAINT platform_setting_key_check
    CHECK (setting_key ~ '^(email|storage|model|limits|retention)\.[a-z0-9_.-]{1,64}$');

ALTER TABLE platform_operation_run DROP CONSTRAINT platform_operation_run_kind_check;
ALTER TABLE platform_operation_run ADD CONSTRAINT platform_operation_run_kind_check
    CHECK (kind IN ('migration', 'backup', 'restore_drill', 'queue_requeue', 'catalog_seed',
                    'retention_purge', 'tombstone_replay'));

-- ------------------------------------------------------------------ 预置保留策略

INSERT INTO platform_setting (setting_key, category, value, is_secret, updated_by)
VALUES ('retention.audit_days', 'retention', '2555'::jsonb, false, 'migration'),
       ('retention.business_log_days', 'retention', '180'::jsonb, false, 'migration'),
       ('retention.recycle_bin_days', 'retention', '30'::jsonb, false, 'migration')
ON CONFLICT (setting_key) DO NOTHING;
