-- 迁移后的结构与数据一致性：任何一条不满足都说明迁移没有真正生效。
DO $$
DECLARE
    rls record;
    policy_count integer;
    invalid_index integer;
    null_tenant bigint;
BEGIN
    SELECT relrowsecurity, relforcerowsecurity INTO rls
      FROM pg_class WHERE oid = 'tenancy_tenant'::regclass;
    IF NOT (rls.relrowsecurity AND rls.relforcerowsecurity) THEN
        RAISE EXCEPTION 'tenancy_tenant 必须同时 ENABLE 与 FORCE RLS，实际 enable=%, force=%',
            rls.relrowsecurity, rls.relforcerowsecurity;
    END IF;

    SELECT count(*) INTO policy_count
      FROM pg_policies WHERE tablename = 'tenancy_tenant';
    IF policy_count <> 1 THEN
        RAISE EXCEPTION 'tenancy_tenant 应恰好有 1 条策略，实际 %', policy_count;
    END IF;

    SELECT count(*) INTO invalid_index
      FROM pg_index WHERE NOT indisvalid;
    IF invalid_index <> 0 THEN
        RAISE EXCEPTION '存在 % 个无效索引（并发建索引失败会留下 INVALID 索引）', invalid_index;
    END IF;

    SELECT count(*) INTO null_tenant FROM platform_outbox WHERE tenant_id IS NULL;
    IF null_tenant <> 0 THEN
        RAISE EXCEPTION 'outbox 存在 % 条无 tenant_id 的事件', null_tenant;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'platform_outbox' AND column_name = 'last_attempt_at'
    ) THEN
        RAISE EXCEPTION 'V3 未生效：platform_outbox.last_attempt_at 不存在';
    END IF;

    -- V4/V5/V6/V7/V8/V9/V10：所有租户表必须同时 ENABLE 与 FORCE RLS，且各恰好一条隔离策略
    FOR rls IN
        SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
          FROM pg_class c
         WHERE c.relname IN ('platform_idempotency', 'platform_job', 'platform_file',
                             'platform_outbox_replay',
                             'work_assistant_device', 'work_assistant_policy',
                             'work_assistant_segment',
                             'tenancy_domain', 'tenancy_quota',
                             'tenancy_business_number_sequence', 'tenancy_business_number',
                             'tenancy_lifecycle_audit', 'tenancy_support_access',
                             'tenancy_profile', 'tenancy_appeal',
                             'organization_person', 'organization_employment',
                             'organization_legal_entity', 'organization_unit',
                             'organization_job_level', 'organization_job',
                             'organization_position', 'organization_cost_center',
                             'organization_location', 'organization_employment_assignment',
                             'organization_reporting_line', 'organization_delegation',
                             'organization_owner_role', 'organization_change',
                             'organization_change_history', 'organization_impact_preview',
                             'identity_user_account', 'identity_actor',
                             'identity_service_account', 'identity_tenant_member',
                             'identity_member_account_history', 'identity_password_history',
                             'identity_invitation', 'identity_mfa_method',
                             'identity_recovery_code', 'identity_mfa_challenge',
                             'identity_session', 'identity_api_token',
                             'identity_identity_provider', 'identity_auth_event',
                             'authorization_role', 'authorization_role_permission',
                             'authorization_assignment', 'authorization_deny',
                             'authorization_field_policy', 'authorization_condition_policy',
                             'authorization_cache_version', 'authorization_decision_log',
                             'authorization_access_request', 'authorization_access_review',
                             'authorization_access_review_item', 'authorization_admin_action')
    LOOP
        IF NOT (rls.relrowsecurity AND rls.relforcerowsecurity) THEN
            RAISE EXCEPTION '% 必须同时 ENABLE 与 FORCE RLS，实际 enable=%, force=%',
                rls.relname, rls.relrowsecurity, rls.relforcerowsecurity;
        END IF;

        SELECT count(*) INTO policy_count FROM pg_policies WHERE tablename = rls.relname;
        IF policy_count <> 1 THEN
            RAISE EXCEPTION '% 应恰好有 1 条策略，实际 %', rls.relname, policy_count;
        END IF;
    END LOOP;

    SELECT count(*) INTO policy_count
      FROM pg_class c
     WHERE c.relname IN ('platform_idempotency', 'platform_job', 'platform_file',
                         'platform_outbox_replay',
                         'work_assistant_device', 'work_assistant_policy',
                         'work_assistant_segment',
                         'tenancy_domain', 'tenancy_quota',
                         'tenancy_business_number_sequence', 'tenancy_business_number',
                         'tenancy_lifecycle_audit', 'tenancy_support_access',
                         'tenancy_profile', 'tenancy_appeal',
                         'organization_person', 'organization_employment',
                         'organization_legal_entity', 'organization_unit',
                         'organization_job_level', 'organization_job',
                         'organization_position', 'organization_cost_center',
                         'organization_location', 'organization_employment_assignment',
                         'organization_reporting_line', 'organization_delegation',
                         'organization_owner_role', 'organization_change',
                         'organization_change_history', 'organization_impact_preview',
                         'identity_user_account', 'identity_actor',
                         'identity_service_account', 'identity_tenant_member',
                         'identity_member_account_history', 'identity_password_history',
                         'identity_invitation', 'identity_mfa_method',
                         'identity_recovery_code', 'identity_mfa_challenge',
                         'identity_session', 'identity_api_token',
                         'identity_identity_provider', 'identity_auth_event',
                         'authorization_role', 'authorization_role_permission',
                         'authorization_assignment', 'authorization_deny',
                         'authorization_field_policy', 'authorization_condition_policy',
                         'authorization_cache_version', 'authorization_decision_log',
                         'authorization_access_request', 'authorization_access_review',
                         'authorization_access_review_item', 'authorization_admin_action');
    IF policy_count <> 57 THEN
        RAISE EXCEPTION 'V4/V5/V6/V7/V8/V9/V10 未完整生效：应有 57 张租户表，实际 %', policy_count;
    END IF;

    -- WP02-07：平台目录投影是设计内例外，必须不启用 RLS，且同步触发器存在
    IF (SELECT relrowsecurity FROM pg_class
         WHERE oid = 'platform_tenant_directory'::regclass) THEN
        RAISE EXCEPTION 'platform_tenant_directory 不应启用 RLS：平台列表必须跨租户读元数据';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'tenancy_tenant_directory_sync' AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V11 未生效：缺少 tenancy_tenant → platform_tenant_directory 同步触发器';
    END IF;
END
$$;

\echo '结构与一致性断言全部通过'
