-- V19：WP04-01 语音工时录入与设备采集链路移除。
--
-- 设计要点：
--   1. 产品不再提供设备采集客户端：删除 device / policy / segment 三张表，
--      回收站与法定留置同步去掉 work_assistant_segment 数据类；
--   2. 语音音频只作为一次性转写输入：worklog_voice_capture 记录对象键、
--      转写文本与删除时间，确认生成工时后立即删除对象存储文件；
--   3. 转写永远保留原始文本（transcript_text），员工编辑结果单独存 edited_text，
--      便于追溯「机器说了什么、人改成了什么」；
--   4. 所有租户表启用并强制 RLS。

-- ------------------------------------------------------------------ 摘除旧数据类

DELETE FROM retention_bin_item WHERE data_class = 'work_assistant_segment';
DELETE FROM retention_legal_hold WHERE data_class = 'work_assistant_segment';

ALTER TABLE retention_bin_item DROP CONSTRAINT retention_bin_item_class_check;
ALTER TABLE retention_bin_item
    ADD CONSTRAINT retention_bin_item_class_check
        CHECK (data_class IN ('file', 'worklog_entry'));

ALTER TABLE retention_legal_hold DROP CONSTRAINT retention_legal_hold_class_check;
ALTER TABLE retention_legal_hold
    ADD CONSTRAINT retention_legal_hold_class_check
        CHECK (data_class IN ('file', 'worklog_entry', 'worklog_weekly_report',
                              'person', 'payroll_result', 'audit'));

DROP TABLE IF EXISTS work_assistant_segment;
DROP TABLE IF EXISTS work_assistant_policy;
DROP TABLE IF EXISTS work_assistant_device;

-- ------------------------------------------------------------------ 语音录入

CREATE TABLE worklog_voice_capture (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenancy_tenant (id),
    person_id         uuid        NOT NULL,
    employment_id     uuid,
    work_date         date        NOT NULL,
    object_key        text        NOT NULL,
    content_type      text        NOT NULL,
    size_bytes        bigint      NOT NULL,
    duration_seconds  integer,
    status            text        NOT NULL DEFAULT 'uploaded',
    transcript_text   text,
    edited_text       text,
    provider          text,
    model             text,
    confidence        numeric(4, 3),
    error_code        text,
    worklog_entry_id  uuid,
    client_request_id text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    transcribed_at    timestamptz,
    audio_deleted_at  timestamptz,
    CONSTRAINT worklog_voice_capture_status_check
        CHECK (status IN ('uploaded', 'transcribed', 'confirmed', 'discarded', 'failed')),
    CONSTRAINT worklog_voice_capture_size_check
        CHECK (size_bytes BETWEEN 1 AND 10485760),
    CONSTRAINT worklog_voice_capture_duration_check
        CHECK (duration_seconds IS NULL OR duration_seconds BETWEEN 1 AND 120),
    CONSTRAINT worklog_voice_capture_confidence_check
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT worklog_voice_capture_client_unique
        UNIQUE (tenant_id, person_id, client_request_id),
    CONSTRAINT worklog_voice_capture_object_unique UNIQUE (object_key)
);

COMMENT ON TABLE worklog_voice_capture IS
    '语音工时录入：音频仅用于一次转写，确认生成工时后立即删除；不含设备标识';
COMMENT ON COLUMN worklog_voice_capture.object_key IS
    '对象存储键；音频删除后保留键与删除时间，便于审计';
COMMENT ON COLUMN worklog_voice_capture.transcript_text IS
    'ASR 原始转写，员工编辑不覆盖该字段';
COMMENT ON COLUMN worklog_voice_capture.edited_text IS
    '员工确认后的文本；与 transcript_text 不一致时说明发生了人工修正';

CREATE INDEX worklog_voice_capture_person_idx
    ON worklog_voice_capture (tenant_id, person_id, created_at DESC);

ALTER TABLE worklog_voice_capture ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklog_voice_capture FORCE ROW LEVEL SECURITY;

CREATE POLICY worklog_voice_capture_isolation ON worklog_voice_capture
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
