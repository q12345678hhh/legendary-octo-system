-- G3 验收演示数据（仅用于演示租户 aaaaaaaa-0000-0000-0000-000000000001）。
--
-- 覆盖 WP03-08 要求的组织结构：老板、主管、三级下属、同级、兼岗、调动、项目成员。
-- 脚本可重复执行；已存在的行不会被覆盖。
--
-- 使用方式（宿主 Windows，WSL pglab 中的 platform-pg-dev）：
--   Get-Content scripts/demo-g3-seed.sql -Raw |
--     wsl -d pglab -u root -- docker exec -i platform-pg-dev psql -U platform_owner -d platform

BEGIN;

SELECT set_config('app.tenant_id', 'aaaaaaaa-0000-0000-0000-000000000001', true);

-- 岗位族：总经理、市场专员
INSERT INTO organization_job (id, tenant_id, code, name, job_family, status) VALUES
 ('11111111-0000-4000-8000-000000000013', 'aaaaaaaa-0000-0000-0000-000000000001',
  'DEMO-GM', '总经理', '管理', 'active'),
 ('11111111-0000-4000-8000-000000000014', 'aaaaaaaa-0000-0000-0000-000000000001',
  'DEMO-MKT', '市场专员', '市场', 'active')
ON CONFLICT (id) DO NOTHING;

-- 市场部：用于兼岗与调动场景
INSERT INTO organization_unit
 (id, tenant_id, parent_id, legal_entity_id, code, name, unit_type, status, sort_order)
VALUES
 ('11111111-0000-4000-8000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000002', '11111111-0000-4000-8000-000000000001',
  'DEMO-MKT-UNIT', '市场部', 'department', 'active', 20)
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_position (id, tenant_id, org_unit_id, job_id, code, name, status) VALUES
 ('11111111-0000-4000-8000-000000000023', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000004', '11111111-0000-4000-8000-000000000014',
  'DEMO-MKT-SPEC', '市场专员', 'active'),
 ('11111111-0000-4000-8000-000000000024', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000002', '11111111-0000-4000-8000-000000000013',
  'DEMO-GM', '总经理', 'active')
ON CONFLICT (id) DO NOTHING;

-- 人员：赵总（老板）、陈专员（三级下属）、刘工（同级 + 兼岗 + 调动）
INSERT INTO organization_person (id, tenant_id, display_name, primary_email, status) VALUES
 ('11111111-0000-4000-8000-000000000034', 'aaaaaaaa-0000-0000-0000-000000000001',
  '赵总', 'demo.owner@example.test', 'active'),
 ('11111111-0000-4000-8000-000000000035', 'aaaaaaaa-0000-0000-0000-000000000001',
  '陈专员', 'demo.chen@example.test', 'active'),
 ('11111111-0000-4000-8000-000000000036', 'aaaaaaaa-0000-0000-0000-000000000001',
  '刘工', 'demo.liu@example.test', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_employment
 (id, tenant_id, person_id, employment_no, status, effective_from, job_title)
VALUES
 ('11111111-0000-4000-8000-000000000045', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000035', 'DEMO-E-CHEN', 'active',
  DATE '2026-06-01', '研发工程师'),
 ('11111111-0000-4000-8000-000000000046', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000036', 'DEMO-E-LIU', 'active',
  DATE '2026-06-01', '研发工程师'),
 ('11111111-0000-4000-8000-000000000047', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000034', 'DEMO-E-OWNER', 'active',
  DATE '2026-06-01', '总经理')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_employment_assignment
 (id, tenant_id, employment_id, position_id, assignment_type, effective_from)
VALUES
 ('11111111-0000-4000-8000-000000000048', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000045', '11111111-0000-4000-8000-000000000022', 'primary',
  DATE '2026-06-01'),
 ('11111111-0000-4000-8000-000000000049', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000046', '11111111-0000-4000-8000-000000000022', 'primary',
  DATE '2026-06-01'),
 ('11111111-0000-4000-8000-000000000050', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000046', '11111111-0000-4000-8000-000000000023', 'secondary',
  DATE '2026-06-01'),
 ('11111111-0000-4000-8000-000000000051', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000047', '11111111-0000-4000-8000-000000000024', 'primary',
  DATE '2026-06-01')
ON CONFLICT (id) DO NOTHING;

-- 汇报线：赵总 → 张主管 → 李工 → 陈专员；王工、刘工与李工同级
INSERT INTO organization_reporting_line
 (id, tenant_id, subordinate_employment_id, manager_employment_id, line_type, effective_from,
  created_by)
VALUES
 ('11111111-0000-4000-8000-000000000055', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000041', '11111111-0000-4000-8000-000000000047', 'solid',
  DATE '2026-06-01', 'g3-demo'),
 ('11111111-0000-4000-8000-000000000056', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000045', '11111111-0000-4000-8000-000000000042', 'solid',
  DATE '2026-06-01', 'g3-demo'),
 ('11111111-0000-4000-8000-000000000057', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000046', '11111111-0000-4000-8000-000000000041', 'solid',
  DATE '2026-06-01', 'g3-demo')
ON CONFLICT (id) DO NOTHING;

-- 老板：organization_owner_role + business-owner 授权
INSERT INTO organization_owner_role
 (id, tenant_id, employment_id, role_type, effective_from, appointed_by)
VALUES
 ('11111111-0000-4000-8000-000000000061', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000047', 'owner', DATE '2026-06-01', 'g3-demo')
ON CONFLICT (id) DO NOTHING;

-- 老板登录账号：与演示主管共用同一口令哈希（Demo#Passw0rd2026）
INSERT INTO identity_user_account
 (id, tenant_id, email, email_normalized, display_name, password_hash, status,
  auth_version, activated_at)
SELECT '11111111-0000-4000-8000-000000000081', 'aaaaaaaa-0000-0000-0000-000000000001',
       'demo.owner@example.test', 'demo.owner@example.test', '赵总', password_hash, 'active',
       1, now()
  FROM identity_user_account
 WHERE id = '11111111-0000-4000-8000-000000000071'
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_actor (id, tenant_id, actor_type, display_name, status, user_id) VALUES
 ('11111111-0000-4000-8000-000000000082', 'aaaaaaaa-0000-0000-0000-000000000001',
  'user', '赵总', 'active', '11111111-0000-4000-8000-000000000081')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_tenant_member
 (id, tenant_id, user_id, person_id, status, is_owner, is_admin)
VALUES
 ('11111111-0000-4000-8000-000000000083', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000081', '11111111-0000-4000-8000-000000000034',
  'active', true, false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO authorization_assignment
 (id, tenant_id, actor_id, member_id, role_code, scope_type, valid_from, reason, granted_by)
VALUES
 (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000082', '11111111-0000-4000-8000-000000000083',
  'business-owner', 'tenant', now(), 'G3 验收演示老板', 'g3-demo')
ON CONFLICT DO NOTHING;

-- 项目成员：客户成功试点
INSERT INTO collab_project (id, tenant_id, org_unit_id, name, description, status) VALUES
 ('11111111-0000-4000-8000-000000000071', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000003', '客户成功试点',
  '跨岗位协作：主管、研发与兼岗成员共同跟进客户问题', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO collab_project_member (tenant_id, project_id, person_id, role) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-4000-8000-000000000071',
  '11111111-0000-4000-8000-000000000031', 'owner'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-4000-8000-000000000071',
  '11111111-0000-4000-8000-000000000032', 'member'),
 ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-0000-4000-8000-000000000071',
  '11111111-0000-4000-8000-000000000036', 'member')
ON CONFLICT DO NOTHING;

-- 调动：刘工由市场部岗位调整到产品研发部岗位（已生效）
INSERT INTO organization_change
 (id, tenant_id, change_type, target_id, effective_date, payload, status, requested_by,
  reason, applied_at)
VALUES
 ('11111111-0000-4000-8000-000000000072', 'aaaaaaaa-0000-0000-0000-000000000001',
  'position_upsert', '11111111-0000-4000-8000-000000000046', current_date - 30,
  jsonb_build_object(
    'kind', 'transfer',
    'personId', '11111111-0000-4000-8000-000000000036',
    'fromOrgUnitId', '11111111-0000-4000-8000-000000000004',
    'fromPositionId', '11111111-0000-4000-8000-000000000023',
    'toOrgUnitId', '11111111-0000-4000-8000-000000000003',
    'toPositionId', '11111111-0000-4000-8000-000000000022'),
  'applied', 'g3-demo', '市场部转入产品研发部', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_change_history
 (id, tenant_id, change_id, entity_type, entity_id, action, effective_date,
  before_state, after_state, actor)
VALUES
 ('11111111-0000-4000-8000-000000000073', 'aaaaaaaa-0000-0000-0000-000000000001',
  '11111111-0000-4000-8000-000000000072', 'employment',
  '11111111-0000-4000-8000-000000000046', 'transfer', current_date - 30,
  jsonb_build_object('orgUnitId', '11111111-0000-4000-8000-000000000004',
                     'positionId', '11111111-0000-4000-8000-000000000023'),
  jsonb_build_object('orgUnitId', '11111111-0000-4000-8000-000000000003',
                     'positionId', '11111111-0000-4000-8000-000000000022'),
  'g3-demo')
ON CONFLICT (id) DO NOTHING;

COMMIT;
