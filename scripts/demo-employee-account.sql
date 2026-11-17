-- 普通员工演示账号：李工（产品研发部）
-- 可重复执行；依赖 demo-base-seed.sql 与 demo-g3-seed.sql 中的人员/部门数据。
-- 登录：demo.li@example.test / Demo#Passw0rd2026

begin;

set local app.tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001';

do $$
begin
    if not exists (select 1 from organization_person
                   where id = '11111111-0000-4000-8000-000000000032'
                     and tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001') then
        raise exception '缺少演示人员 李工，请先执行 demo-base-seed.sql / demo-g3-seed.sql';
    end if;
end
$$;

insert into identity_user_account (
    id, tenant_id, email, email_normalized, display_name, password_hash,
    status, activated_at
) values (
    '11111111-0000-4000-8000-000000000091',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'demo.li@example.test',
    'demo.li@example.test',
    '李工',
    '$2a$12$7NjRDRVX.ncn0sSBC.zRC.QXnwC3TjeBeQaiIDE5ndx2ISIvtip9O',
    'active',
    now()
)
on conflict (tenant_id, email_normalized) do nothing;

insert into identity_actor (id, tenant_id, actor_type, display_name, status, user_id)
values (
    '11111111-0000-4000-8000-000000000092',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'user',
    '李工',
    'active',
    '11111111-0000-4000-8000-000000000091'
)
on conflict (tenant_id, user_id) where user_id is not null do nothing;

insert into identity_tenant_member (id, tenant_id, user_id, person_id, status, is_owner, is_admin)
values (
    '11111111-0000-4000-8000-000000000093',
    'aaaaaaaa-0000-0000-0000-000000000001',
    '11111111-0000-4000-8000-000000000091',
    '11111111-0000-4000-8000-000000000032',
    'active',
    false,
    false
)
on conflict (tenant_id, user_id) do nothing;

-- 本人范围：工时与周报、单据提交等自助能力
insert into authorization_assignment (
    tenant_id, actor_id, member_id, role_code, scope_type, reason, granted_by
)
select
    'aaaaaaaa-0000-0000-0000-000000000001',
    '11111111-0000-4000-8000-000000000092',
    '11111111-0000-4000-8000-000000000093',
    'employee-self',
    'self',
    '本地演示：普通员工本人范围',
    'local-bootstrap'
where not exists (
    select 1 from authorization_assignment
    where tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001'
      and actor_id = '11111111-0000-4000-8000-000000000092'
      and role_code = 'employee-self'
      and revoked_at is null
);

-- 部门范围：产品研发部协作与部门工作台只读
insert into authorization_assignment (
    tenant_id, actor_id, member_id, role_code, scope_type, scope_id, reason, granted_by
)
select
    'aaaaaaaa-0000-0000-0000-000000000001',
    '11111111-0000-4000-8000-000000000092',
    '11111111-0000-4000-8000-000000000093',
    'employee',
    'org_unit',
    '11111111-0000-4000-8000-000000000003',
    '本地演示：普通员工部门范围',
    'local-bootstrap'
where not exists (
    select 1 from authorization_assignment
    where tenant_id = 'aaaaaaaa-0000-0000-0000-000000000001'
      and actor_id = '11111111-0000-4000-8000-000000000092'
      and role_code = 'employee'
      and revoked_at is null
);

commit;
