-- V13 部门工作区：每个未归档组织单元自动拥有一个空间，并预置 Phase 3 权限码。
--
-- 边界：
-- 1. 成员、职位、汇报层级不在这里冗余存储，统一按生效日期从组织模块投影；
-- 2. 空间只保存「部门级开关与提醒时间」，不保存任何员工个人数据；
-- 3. 组织单元插入/状态变化由触发器同步空间状态，避免应用层双写漂移。

CREATE TABLE department_space (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id uuid        NOT NULL,
    name        text        NOT NULL,
    status      text        NOT NULL DEFAULT 'active',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    CONSTRAINT department_space_status_check CHECK (status IN ('active', 'archived')),
    CONSTRAINT department_space_name_check CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT department_space_unit_unique UNIQUE (tenant_id, org_unit_id),
    CONSTRAINT department_space_unit_fk FOREIGN KEY (tenant_id, org_unit_id)
        REFERENCES organization_unit (tenant_id, id)
);

COMMENT ON TABLE department_space IS
    '部门工作区：与组织单元一一对应；成员与职位为实时投影，不在此表冗余';
COMMENT ON COLUMN department_space.status IS
    '镜像 organization_unit.status：部门归档后空间只读归档，恢复由组织模块决定';

CREATE INDEX department_space_tenant_idx ON department_space (tenant_id, status, name);

ALTER TABLE department_space ENABLE ROW LEVEL SECURITY;
ALTER TABLE department_space FORCE ROW LEVEL SECURITY;

CREATE POLICY department_space_isolation ON department_space
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE department_space_setting (
    tenant_id               uuid NOT NULL REFERENCES tenancy_tenant (id),
    org_unit_id             uuid NOT NULL,
    day_end_time            time NOT NULL DEFAULT '18:00',
    week_report_due_weekday integer NOT NULL DEFAULT 5,
    updated_by              text NOT NULL DEFAULT 'system',
    updated_at              timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, org_unit_id),
    CONSTRAINT department_space_setting_weekday_check
        CHECK (week_report_due_weekday BETWEEN 1 AND 7),
    CONSTRAINT department_space_setting_unit_fk FOREIGN KEY (tenant_id, org_unit_id)
        REFERENCES department_space (tenant_id, org_unit_id) ON DELETE CASCADE
);

COMMENT ON TABLE department_space_setting IS
    '部门级提醒设置：下班汇总时间与周报截止星期；不含任何个人工时内容';
COMMENT ON COLUMN department_space_setting.week_report_due_weekday IS
    'ISO 星期：1=周一 … 7=周日；仅用于生成提醒，不改变提交权限';

ALTER TABLE department_space_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE department_space_setting FORCE ROW LEVEL SECURITY;

CREATE POLICY department_space_setting_isolation ON department_space_setting
    FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------------ 回填与自动同步

DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT DISTINCT tenant_id FROM organization_unit LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);
        INSERT INTO department_space (tenant_id, org_unit_id, name, status, archived_at)
        SELECT tenant_id, id, name,
               CASE WHEN status = 'active' THEN 'active' ELSE 'archived' END,
               CASE WHEN status = 'active' THEN NULL ELSE now() END
          FROM organization_unit
         WHERE tenant_id = t.tenant_id
        ON CONFLICT (tenant_id, org_unit_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

CREATE FUNCTION department_space_sync() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO department_space (tenant_id, org_unit_id, name, status)
    VALUES (NEW.tenant_id, NEW.id, NEW.name,
            CASE WHEN NEW.status = 'active' THEN 'active' ELSE 'archived' END)
    ON CONFLICT (tenant_id, org_unit_id) DO UPDATE
       SET name = EXCLUDED.name,
           status = EXCLUDED.status,
           archived_at = CASE WHEN EXCLUDED.status = 'archived' THEN now() ELSE NULL END,
           updated_at = now();
    RETURN NEW;
END $$;

COMMENT ON FUNCTION department_space_sync() IS
    '组织单元插入或状态变化时同步部门空间；归档只改状态，不删除历史引用';

CREATE TRIGGER organization_unit_department_space_trigger
    AFTER INSERT OR UPDATE OF name, status ON organization_unit
    FOR EACH ROW EXECUTE FUNCTION department_space_sync();

-- ------------------------------------------------------------------ Phase 3 权限码

INSERT INTO authorization_permission (code, resource_type, action, description, sensitive) VALUES
    ('department.workspace.read', 'department.workspace', 'read', '查看部门工作区与成员投影', false),
    ('department.workspace.manage', 'department.workspace', 'manage', '维护部门工作区设置', true),
    ('worklog.time.read', 'worklog.time', 'read', '查看本人或管理范围内的工时', true),
    ('worklog.time.write', 'worklog.time', 'write', '录入与修改本人工时草稿', false),
    ('worklog.time.review', 'worklog.time', 'review', '审核、评论与退回下属工时', true),
    ('worklog.time.export', 'worklog.time', 'export', '合规导出工时记录', true),
    ('worklog.report.read', 'worklog.report', 'read', '查看本人或管理范围内的周报', true),
    ('worklog.report.submit', 'worklog.report', 'submit', '提交本人周报', false),
    ('worklog.report.review', 'worklog.report', 'review', '审核与退回下属周报', true),
    ('insight.work.read', 'insight.work', 'read', '查看权限感知的工作洞察', true),
    ('insight.work.export', 'insight.work', 'export', '导出工作洞察', true),
    ('collaboration.channel.read', 'collaboration.channel', 'read', '查看有权访问的频道', false),
    ('collaboration.channel.manage', 'collaboration.channel', 'manage', '创建、归档频道与维护成员', true),
    ('collaboration.post.write', 'collaboration.post', 'write', '在有权频道发帖与评论', false),
    ('collaboration.task.write', 'collaboration.task', 'write', '维护任务、里程碑与行动项', false),
    ('collaboration.page.manage', 'collaboration.page', 'manage', '维护知识页与决定记录', true),
    ('workflow.definition.manage', 'workflow.definition', 'manage', '维护审批定义与版本', true),
    ('workflow.instance.start', 'workflow.instance', 'start', '发起流程实例', false),
    ('workflow.task.act', 'workflow.task', 'act', '处理本人审批任务', false),
    ('workflow.instance.admin', 'workflow.instance', 'admin', '补偿、取消与异常处置', true),
    ('appcatalog.module.read', 'appcatalog.module', 'read', '查看模块目录', false),
    ('appcatalog.suggestion.create', 'appcatalog.suggestion', 'create', '发起模块建议', false),
    ('appcatalog.suggestion.review', 'appcatalog.suggestion', 'review', '受理、驳回与要求补充模块建议', true),
    ('appcatalog.module.manage', 'appcatalog.module', 'manage', '维护模块目录与配置负责人', true);
