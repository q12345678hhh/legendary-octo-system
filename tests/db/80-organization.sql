-- WP02-03：组织结构、主/兼岗、汇报线、委派、老板角色、变更历史与预览票据的数据库断言。
-- 以 platform_app 身份执行；每个租户操作都在显式事务内用 set_config(..., true) 建立上下文。

\set org_e '''eeeeeeee-0000-4000-8000-000000000005'''
\set org_f '''ffffffff-0000-4000-8000-000000000006'''

-- 1. 租户甲：法人 → 三级部门 → 职级/岗位/职位 → 雇佣 → 主岗/兼岗 → 实线/虚线 → 委派 → 老板/受托人
BEGIN;
SELECT set_config('app.tenant_id', :org_e, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:org_e, 'org-epsilon', '组织甲', 'active', 'standard');

INSERT INTO organization_legal_entity (id, tenant_id, code, name, registration_no)
VALUES ('e0000000-0000-4000-8000-000000000101', :org_e, 'LE-001', '示例科技有限公司', '91310000TEST0001');

INSERT INTO organization_unit (id, tenant_id, parent_id, legal_entity_id, code, name, unit_type, sort_order)
VALUES ('e0000000-0000-4000-8000-000000000201', :org_e, NULL,
        'e0000000-0000-4000-8000-000000000101', 'U-ROOT', '总部', 'company', 0),
       ('e0000000-0000-4000-8000-000000000202', :org_e, 'e0000000-0000-4000-8000-000000000201',
        'e0000000-0000-4000-8000-000000000101', 'U-RD', '研发部', 'department', 1),
       ('e0000000-0000-4000-8000-000000000203', :org_e, 'e0000000-0000-4000-8000-000000000202',
        'e0000000-0000-4000-8000-000000000101', 'U-RD-PLAT', '平台组', 'team', 2);

INSERT INTO organization_job_level (id, tenant_id, code, name, rank)
VALUES ('e0000000-0000-4000-8000-000000000301', :org_e, 'L5', '高级', 5);

INSERT INTO organization_job (id, tenant_id, code, name, job_family, default_level_id)
VALUES ('e0000000-0000-4000-8000-000000000302', :org_e, 'J-BE', '后端工程师', '研发',
        'e0000000-0000-4000-8000-000000000301');

INSERT INTO organization_position (id, tenant_id, org_unit_id, job_id, code, name, headcount_limit)
VALUES ('e0000000-0000-4000-8000-000000000303', :org_e,
        'e0000000-0000-4000-8000-000000000203', 'e0000000-0000-4000-8000-000000000302',
        'P-BE-01', '后端工程师（平台组）', 3);

INSERT INTO organization_cost_center (id, tenant_id, code, name, legal_entity_id)
VALUES ('e0000000-0000-4000-8000-000000000304', :org_e, 'CC-RD', '研发成本中心',
        'e0000000-0000-4000-8000-000000000101');

INSERT INTO organization_location (id, tenant_id, code, name, address, timezone)
VALUES ('e0000000-0000-4000-8000-000000000305', :org_e, 'LOC-SH', '上海办公室',
        '上海市浦东新区', 'Asia/Shanghai');

INSERT INTO organization_person (id, tenant_id, display_name)
VALUES ('e0000000-0000-4000-8000-000000000401', :org_e, '甲经理'),
       ('e0000000-0000-4000-8000-000000000402', :org_e, '乙主管'),
       ('e0000000-0000-4000-8000-000000000403', :org_e, '丙工程师');

INSERT INTO organization_employment (id, tenant_id, person_id, employment_no, effective_from, job_title)
VALUES ('e0000000-0000-4000-8000-000000000501', :org_e,
        'e0000000-0000-4000-8000-000000000401', 'E-A', DATE '2023-01-01', '研发负责人'),
       ('e0000000-0000-4000-8000-000000000502', :org_e,
        'e0000000-0000-4000-8000-000000000402', 'E-B', DATE '2023-06-01', '平台组主管'),
       ('e0000000-0000-4000-8000-000000000503', :org_e,
        'e0000000-0000-4000-8000-000000000403', 'E-C', DATE '2024-03-01', '后端工程师');

-- 主岗：丙 → 平台组职位；兼岗：乙同时挂同一职位（兼岗不唯一）
INSERT INTO organization_employment_assignment
    (tenant_id, employment_id, position_id, assignment_type, effective_from)
VALUES (:org_e, 'e0000000-0000-4000-8000-000000000503',
        'e0000000-0000-4000-8000-000000000303', 'primary', DATE '2024-03-01'),
       (:org_e, 'e0000000-0000-4000-8000-000000000502',
        'e0000000-0000-4000-8000-000000000303', 'secondary', DATE '2024-09-01');

-- 三级实线汇报：丙 → 乙 → 甲，另有甲 → 丙 的虚线（不构成环）
INSERT INTO organization_reporting_line
    (tenant_id, subordinate_employment_id, manager_employment_id, line_type,
     effective_from, created_by)
VALUES (:org_e, 'e0000000-0000-4000-8000-000000000503',
        'e0000000-0000-4000-8000-000000000502', 'solid', DATE '2024-03-01', 'verify'),
       (:org_e, 'e0000000-0000-4000-8000-000000000502',
        'e0000000-0000-4000-8000-000000000501', 'solid', DATE '2023-06-01', 'verify'),
       (:org_e, 'e0000000-0000-4000-8000-000000000501',
        'e0000000-0000-4000-8000-000000000503', 'dotted', DATE '2024-03-01', 'verify');

-- 限时代管：甲委派乙管理研发部子树
INSERT INTO organization_delegation
    (tenant_id, delegator_employment_id, delegate_employment_id, scope_org_unit_id,
     reason, effective_from, effective_to, created_by)
VALUES (:org_e, 'e0000000-0000-4000-8000-000000000501',
        'e0000000-0000-4000-8000-000000000502',
        'e0000000-0000-4000-8000-000000000202', '休假代管', CURRENT_DATE - 1,
        CURRENT_DATE + 30, 'verify');

-- 老板与受托人：都是独立业务角色，与 identity_tenant_member 无关
INSERT INTO organization_owner_role
    (tenant_id, employment_id, role_type, effective_from, appointed_by)
VALUES (:org_e, 'e0000000-0000-4000-8000-000000000501', 'owner', DATE '2023-01-01', 'verify'),
       (:org_e, 'e0000000-0000-4000-8000-000000000502', 'trustee', DATE '2023-06-01', 'verify');

-- 未来变更单 + 已应用变更 + 历史快照 + 影响预览票据
INSERT INTO organization_change
    (id, tenant_id, change_type, target_id, effective_date, payload, status, requested_by, reason)
VALUES ('e0000000-0000-4000-8000-000000000601', :org_e, 'unit_move',
        'e0000000-0000-4000-8000-000000000203', DATE '2099-01-01',
        '{"targetParentId":"e0000000-0000-4000-8000-000000000201"}'::jsonb, 'scheduled',
        'verify', '规划中的组织调整'),
       ('e0000000-0000-4000-8000-000000000602', :org_e, 'unit_update',
        'e0000000-0000-4000-8000-000000000202', DATE '2024-01-01',
        '{"name":"研发中心"}'::jsonb, 'applied', 'verify', '部门更名');

INSERT INTO organization_change_history
    (tenant_id, change_id, entity_type, entity_id, action, effective_date,
     before_state, after_state, actor)
VALUES (:org_e, 'e0000000-0000-4000-8000-000000000602', 'org_unit',
        'e0000000-0000-4000-8000-000000000202', 'update', DATE '2024-01-01',
        '{"name":"研发部"}'::jsonb, '{"name":"研发中心"}'::jsonb, 'verify');

INSERT INTO organization_impact_preview
    (id, tenant_id, unit_id, target_parent_id, descendant_count, employment_count,
     position_count, reporting_line_count, delegation_count, scheduled_change_count,
     created_by, expires_at)
VALUES ('e0000000-0000-4000-8000-000000000701', :org_e,
        'e0000000-0000-4000-8000-000000000202', 'e0000000-0000-4000-8000-000000000201',
        1, 2, 1, 2, 1, 1, 'verify', now() + interval '10 minutes');
COMMIT;

-- 2. 租户乙：另一个法人 + 部门，用于跨租户引用断言
BEGIN;
SELECT set_config('app.tenant_id', :org_f, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:org_f, 'org-zeta', '组织乙', 'active', 'standard');

INSERT INTO organization_legal_entity (id, tenant_id, code, name)
VALUES ('f0000000-0000-4000-8000-000000000101', :org_f, 'LE-001', '乙公司');

INSERT INTO organization_unit (id, tenant_id, parent_id, legal_entity_id, code, name)
VALUES ('f0000000-0000-4000-8000-000000000201', :org_f, NULL,
        'f0000000-0000-4000-8000-000000000101', 'U-ROOT', '乙总部');
COMMIT;

-- 3. 约束：跨租户父级、重复主岗、重复实线、自汇报、委派自派、历史不可改
BEGIN;
SELECT set_config('app.tenant_id', :org_e, true);
DO $$
BEGIN
    BEGIN
        INSERT INTO organization_unit (tenant_id, parent_id, legal_entity_id, code, name)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'f0000000-0000-4000-8000-000000000201',
                'e0000000-0000-4000-8000-000000000101', 'U-ILLEGAL', '跨租户父级');
        RAISE EXCEPTION '跨租户父级竟然插入成功';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO organization_employment_assignment
            (tenant_id, employment_id, position_id, assignment_type, effective_from)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'e0000000-0000-4000-8000-000000000503',
                'e0000000-0000-4000-8000-000000000303', 'primary', DATE '2026-01-01');
        RAISE EXCEPTION '同一雇佣出现第二条未结束主岗竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO organization_reporting_line
            (tenant_id, subordinate_employment_id, manager_employment_id, line_type,
             effective_from, created_by)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'e0000000-0000-4000-8000-000000000503',
                'e0000000-0000-4000-8000-000000000501', 'solid', DATE '2026-01-01', 'verify');
        RAISE EXCEPTION '同一员工出现第二条未结束实线竟然成功';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO organization_reporting_line
            (tenant_id, subordinate_employment_id, manager_employment_id, line_type,
             effective_from, created_by)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'e0000000-0000-4000-8000-000000000503',
                'e0000000-0000-4000-8000-000000000503', 'dotted', DATE '2026-01-01', 'verify');
        RAISE EXCEPTION '自汇报竟然成功';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO organization_delegation
            (tenant_id, delegator_employment_id, delegate_employment_id, scope_org_unit_id,
             reason, effective_from, effective_to, created_by)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'e0000000-0000-4000-8000-000000000501',
                'e0000000-0000-4000-8000-000000000501',
                'e0000000-0000-4000-8000-000000000202', '自派', DATE '2026-01-01',
                DATE '2026-02-01', 'verify');
        RAISE EXCEPTION '委派给自己竟然成功';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        UPDATE organization_change_history
           SET actor = 'tampered'
         WHERE entity_id = 'e0000000-0000-4000-8000-000000000202';
        RAISE EXCEPTION '变更历史竟然可以被修改';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;

    BEGIN
        DELETE FROM organization_change_history
         WHERE entity_id = 'e0000000-0000-4000-8000-000000000202';
        RAISE EXCEPTION '变更历史竟然可以被删除';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 4. 三级汇报链：从丙出发向上只能走两级实线到乙、甲（按当前生效日）
BEGIN;
SELECT set_config('app.tenant_id', :org_e, true);
DO $$
DECLARE
    chain_depth integer;
    leaf text;
BEGIN
    WITH RECURSIVE up AS (
        SELECT r.subordinate_employment_id, r.manager_employment_id, 1 AS depth
          FROM organization_reporting_line r
         WHERE r.line_type = 'solid'
           AND r.effective_from <= CURRENT_DATE
           AND (r.effective_to IS NULL OR r.effective_to > CURRENT_DATE)
        UNION ALL
        SELECT up.subordinate_employment_id, r.manager_employment_id, up.depth + 1
          FROM up
          JOIN organization_reporting_line r
            ON r.subordinate_employment_id = up.manager_employment_id
           AND r.line_type = 'solid'
           AND r.effective_from <= CURRENT_DATE
           AND (r.effective_to IS NULL OR r.effective_to > CURRENT_DATE)
    )
    SELECT max(depth) INTO chain_depth
      FROM up
     WHERE subordinate_employment_id = 'e0000000-0000-4000-8000-000000000503';

    IF COALESCE(chain_depth, 0) <> 2 THEN
        RAISE EXCEPTION '三级实线汇报链深度应为 2，实际 %', chain_depth;
    END IF;

    SELECT manager_employment_id::text INTO leaf
      FROM organization_reporting_line
     WHERE subordinate_employment_id = 'e0000000-0000-4000-8000-000000000502'
       AND line_type = 'solid';
    IF leaf IS NULL THEN
        RAISE EXCEPTION '乙主管没有实线经理';
    END IF;
END
$$;
COMMIT;

-- 5. 主/兼岗与代管都能查到当前生效行
BEGIN;
SELECT set_config('app.tenant_id', :org_e, true);
DO $$
DECLARE
    primaries integer;
    secondaries integer;
    delegations integer;
    owners integer;
    trustees integer;
BEGIN
    SELECT count(*) INTO primaries FROM organization_employment_assignment
     WHERE assignment_type = 'primary' AND effective_to IS NULL;
    SELECT count(*) INTO secondaries FROM organization_employment_assignment
     WHERE assignment_type = 'secondary' AND effective_to IS NULL;
    SELECT count(*) INTO delegations FROM organization_delegation
     WHERE effective_from <= CURRENT_DATE AND effective_to > CURRENT_DATE;
    SELECT count(*) INTO owners FROM organization_owner_role
     WHERE role_type = 'owner'
       AND effective_from <= CURRENT_DATE AND (effective_to IS NULL OR effective_to > CURRENT_DATE);
    SELECT count(*) INTO trustees FROM organization_owner_role
     WHERE role_type = 'trustee'
       AND effective_from <= CURRENT_DATE AND (effective_to IS NULL OR effective_to > CURRENT_DATE);

    IF primaries <> 1 THEN RAISE EXCEPTION '当前应有 1 条主岗，实际 %', primaries; END IF;
    IF secondaries <> 1 THEN RAISE EXCEPTION '当前应有 1 条兼岗，实际 %', secondaries; END IF;
    IF delegations <> 1 THEN RAISE EXCEPTION '当前应有 1 条生效代管，实际 %', delegations; END IF;
    IF owners <> 1 THEN RAISE EXCEPTION '当前应有 1 名老板，实际 %', owners; END IF;
    IF trustees <> 1 THEN RAISE EXCEPTION '当前应有 1 名受托人，实际 %', trustees; END IF;
END
$$;
COMMIT;

-- 6. 无租户上下文时 14 张组织表必须全部返回 0 行
DO $$
DECLARE
    table_name text;
    visible bigint;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'organization_legal_entity', 'organization_unit', 'organization_job_level',
        'organization_job', 'organization_position', 'organization_cost_center',
        'organization_location', 'organization_employment_assignment',
        'organization_reporting_line', 'organization_delegation',
        'organization_owner_role', 'organization_change',
        'organization_change_history', 'organization_impact_preview'
    ] LOOP
        EXECUTE format('SELECT count(*) FROM %I', table_name) INTO visible;
        IF visible <> 0 THEN
            RAISE EXCEPTION '无上下文时 % 应返回 0 行，实际 %', table_name, visible;
        END IF;
    END LOOP;
END
$$;

-- 7. 乙租户上下文看不到甲租户的组织数据，也写不进甲租户的部门
BEGIN;
SELECT set_config('app.tenant_id', :org_f, true);
DO $$
DECLARE
    units bigint;
BEGIN
    SELECT count(*) INTO units FROM organization_unit;
    IF units <> 1 THEN
        RAISE EXCEPTION '乙租户应只看到自己的 1 个部门，实际 %', units;
    END IF;

    BEGIN
        INSERT INTO organization_unit (tenant_id, legal_entity_id, code, name)
        VALUES ('eeeeeeee-0000-4000-8000-000000000005',
                'e0000000-0000-4000-8000-000000000101', 'U-HIJACK', '越权部门');
        RAISE EXCEPTION '乙租户竟然能写甲租户部门';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END
$$;
COMMIT;

\echo 'WP02-03 组织结构数据库断言通过'
