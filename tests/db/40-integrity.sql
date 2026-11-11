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
END
$$;

\echo '结构与一致性断言全部通过'
