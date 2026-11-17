-- 演示基础组织数据（仅用于租户 aaaaaaaa-0000-0000-0000-000000000001）。
--
-- demo-g3-seed.sql 与 demo-worklog-two-weeks.sql 依赖本脚本创建的总部/研发部、
-- 岗位、主管与两名同级员工；先执行本脚本，再执行后两者。
-- 脚本可重复执行；已存在的行不会被覆盖。
--
-- 使用方式（WSL pglab 中的 PostgreSQL 16）：
--   wsl -d pglab -e su postgres -c "psql -d platform -f /mnt/d/Projects/temp_20261109_01/scripts/demo-base-seed.sql"

BEGIN;

SELECT set_config('app.tenant_id', 'aaaaaaaa-0000-0000-0000-000000000001', true);

INSERT INTO organization_legal_entity (id, tenant_id, code, name, status) VALUES
 ('11111111-0000-4000-8000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
  'DEMO-LE', '演示科技有限公司', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_unit
 (id, tenant_id, parent_id, legal_entity_id, code, name, unit_type, status, sort_order)
VALUES
 ('11111111-0000-4000-8000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
  NULL, '11111111-0000-4000-8000-000000000001',
  'DEMO-HQ', '总部', 'company', 'active', 0),
 ('11111111-0000-4000-8000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000002', '11111111-0000-4000-8000-000000000001',
  'DEMO-RD', '产品研发部', 'department', 'active', 10)
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_job (id, tenant_id, code, name, job_family, status) VALUES
 ('11111111-0000-4000-8000-000000000011', 'aaaaaaaa-0000-0000-0000-000000000001',
  'DEMO-RD-MGR', '研发经理', '研发', 'active'),
 ('11111111-0000-4000-8000-000000000012', 'aaaaaaaa-0000-0000-0000-000000000001',
  'DEMO-RD-ENG', '研发工程师', '研发', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_position (id, tenant_id, org_unit_id, job_id, code, name, status) VALUES
 ('11111111-0000-4000-8000-000000000021', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000003', '11111111-0000-4000-8000-000000000011',
  'DEMO-RD-LEAD', '研发主管', 'active'),
 ('11111111-0000-4000-8000-000000000022', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000003', '11111111-0000-4000-8000-000000000012',
  'DEMO-RD-ENG', '研发工程师', 'active')
ON CONFLICT (id) DO NOTHING;

-- 张主管（已由本地登录账号创建为 演示管理员）、李工、王工
INSERT INTO organization_person (id, tenant_id, display_name, primary_email, status) VALUES
 ('11111111-0000-4000-8000-000000000032', 'aaaaaaaa-0000-0000-0000-000000000001',
  '李工', 'demo.li@example.test', 'active'),
 ('11111111-0000-4000-8000-000000000033', 'aaaaaaaa-0000-0000-0000-000000000001',
  '王工', 'demo.wang@example.test', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_employment
 (id, tenant_id, person_id, employment_no, status, effective_from, job_title)
VALUES
 ('11111111-0000-4000-8000-000000000041', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000031', 'DEMO-E-ZHANG', 'active',
  DATE '2026-06-01', '研发主管'),
 ('11111111-0000-4000-8000-000000000042', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000032', 'DEMO-E-LI', 'active',
  DATE '2026-06-01', '研发工程师'),
 ('11111111-0000-4000-8000-000000000043', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000033', 'DEMO-E-WANG', 'active',
  DATE '2026-06-01', '研发工程师')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_employment_assignment
 (id, tenant_id, employment_id, position_id, assignment_type, effective_from)
VALUES
 ('11111111-0000-4000-8000-000000000042', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000041', '11111111-0000-4000-8000-000000000021', 'primary',
  DATE '2026-06-01'),
 ('11111111-0000-4000-8000-000000000043', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000042', '11111111-0000-4000-8000-000000000022', 'primary',
  DATE '2026-06-01'),
 ('11111111-0000-4000-8000-000000000044', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000043', '11111111-0000-4000-8000-000000000022', 'primary',
  DATE '2026-06-01')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_reporting_line
 (id, tenant_id, subordinate_employment_id, manager_employment_id, line_type, effective_from,
  created_by)
VALUES
 ('11111111-0000-4000-8000-000000000052', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000042', '11111111-0000-4000-8000-000000000041', 'solid',
  DATE '2026-06-01', 'base-demo'),
 ('11111111-0000-4000-8000-000000000053', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000043', '11111111-0000-4000-8000-000000000041', 'solid',
  DATE '2026-06-01', 'base-demo')
ON CONFLICT (id) DO NOTHING;

COMMIT;
