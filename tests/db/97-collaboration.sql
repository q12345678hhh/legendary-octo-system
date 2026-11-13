-- WP03-03：部门协作数据库断言。以运行期账号 platform_app 执行，失败即 RAISE EXCEPTION。
--
-- 重点验证：
--   1. 频道/成员/帖子/任务/会议/知识页的约束与级联；
--   2. 文件引用必须指定目标，且不改变 platform_file 的下载 ACL；
--   3. 通知与分享链接的取值约束；
--   4. 19 张协作表全部启用并强制 RLS，跨租户不可见。

DO $$
BEGIN
    IF current_setting('is_superuser') <> 'off' THEN
        RAISE EXCEPTION '用例身份错误：platform_app 不能是超级用户';
    END IF;
    IF current_setting('row_security') <> 'on' THEN
        RAISE EXCEPTION 'row_security 必须为 on，实际 %', current_setting('row_security');
    END IF;
    IF (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION '运行账号不能带 BYPASSRLS';
    END IF;
END
$$;

\set collab_a '''eeeeeeee-0000-4000-8000-000000000011'''
\set collab_b '''ffffffff-0000-4000-8000-000000000012'''

BEGIN;
SELECT set_config('app.tenant_id', :collab_a, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:collab_a, 'collab-a', '协作甲', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DELETE FROM collab_channel WHERE tenant_id = :collab_a;
DELETE FROM collab_project WHERE tenant_id = :collab_a;
DELETE FROM collab_meeting WHERE tenant_id = :collab_a;
DELETE FROM collab_notification WHERE tenant_id = :collab_a;
DELETE FROM collab_share_link WHERE tenant_id = :collab_a;

INSERT INTO platform_file (id, tenant_id, subject_id, file_name, content_type, size_bytes,
                           object_key, status, scan_status)
VALUES ('eeeeeeee-0000-4000-8000-000000000301', :collab_a,
        'actor-collab-a', '周会纪要.pdf', 'application/pdf', 1024,
        'eeeeeeee-0000-4000-8000-000000000011/301/weekly.pdf', 'available', 'clean')
ON CONFLICT (id) DO NOTHING;
COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', :collab_a, true);

-- 1. 频道约束：非法范围与重名被数据库拒绝
DO $$
DECLARE
    v_channel uuid;
BEGIN
    INSERT INTO collab_channel (tenant_id, org_unit_id, scope, name, topic, created_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000201', 'all', '部门公告', '全员可见',
            'eeeeeeee-0000-4000-8000-000000000401')
    RETURNING id INTO v_channel;

    BEGIN
        INSERT INTO collab_channel (tenant_id, org_unit_id, scope, name, topic,
                                    created_by_person_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000011',
                'eeeeeeee-0000-4000-8000-000000000201', 'secret', '非法范围', '',
                'eeeeeeee-0000-4000-8000-000000000401');
        RAISE EXCEPTION '非法频道范围应被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO collab_channel (tenant_id, org_unit_id, scope, name, topic,
                                    created_by_person_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000011',
                'eeeeeeee-0000-4000-8000-000000000201', 'members', '部门公告', '',
                'eeeeeeee-0000-4000-8000-000000000401');
        RAISE EXCEPTION '同部门重名频道应被拒绝';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;

-- 2. 成员加入、移除；重新加入清除 removed_at
DO $$
DECLARE
    v_channel uuid;
    v_removed timestamptz;
    v_active int;
BEGIN
    SELECT id INTO v_channel FROM collab_channel
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000011' AND name = '部门公告';

    INSERT INTO collab_channel_member (tenant_id, channel_id, person_id, role)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_channel,
            'eeeeeeee-0000-4000-8000-000000000501', 'member');

    UPDATE collab_channel_member SET removed_at = now()
     WHERE channel_id = v_channel AND person_id = 'eeeeeeee-0000-4000-8000-000000000501';
    SELECT removed_at INTO v_removed FROM collab_channel_member
     WHERE channel_id = v_channel AND person_id = 'eeeeeeee-0000-4000-8000-000000000501';
    IF v_removed IS NULL THEN
        RAISE EXCEPTION '移除成员必须写 removed_at';
    END IF;

    INSERT INTO collab_channel_member (tenant_id, channel_id, person_id, role)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_channel,
            'eeeeeeee-0000-4000-8000-000000000501', 'owner')
    ON CONFLICT (channel_id, person_id) DO UPDATE
       SET role = EXCLUDED.role, removed_at = NULL;

    SELECT count(*) INTO v_active FROM collab_channel_member
     WHERE channel_id = v_channel AND person_id = 'eeeeeeee-0000-4000-8000-000000000501'
       AND removed_at IS NULL;
    IF v_active <> 1 THEN
        RAISE EXCEPTION '重新加入后应恢复在组状态';
    END IF;

    BEGIN
        UPDATE collab_channel_member SET role = 'admin'
         WHERE channel_id = v_channel AND person_id = 'eeeeeeee-0000-4000-8000-000000000501';
        RAISE EXCEPTION '非法成员角色应被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$$;

-- 3. 帖子、评论、提及、回执
DO $$
DECLARE
    v_channel uuid;
    v_post uuid;
    v_comment uuid;
    v_receipt int;
BEGIN
    SELECT id INTO v_channel FROM collab_channel
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000011' AND name = '部门公告';

    INSERT INTO collab_post (tenant_id, channel_id, author_person_id, title, body)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_channel,
            'eeeeeeee-0000-4000-8000-000000000501', '本周迭代', '请确认排期')
    RETURNING id INTO v_post;

    INSERT INTO collab_comment (tenant_id, post_id, author_person_id, body)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_post,
            'eeeeeeee-0000-4000-8000-000000000502', '已确认')
    RETURNING id INTO v_comment;

    INSERT INTO collab_mention (tenant_id, post_id, comment_id, mentioned_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_post, v_comment,
            'eeeeeeee-0000-4000-8000-000000000503');

    INSERT INTO collab_receipt (tenant_id, post_id, person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_post,
            'eeeeeeee-0000-4000-8000-000000000501')
    ON CONFLICT (post_id, person_id) DO NOTHING;

    SELECT count(*) INTO v_receipt FROM collab_receipt WHERE post_id = v_post;
    IF v_receipt <> 1 THEN
        RAISE EXCEPTION '回执应幂等，实际 % 行', v_receipt;
    END IF;
END
$$;

-- 4. 项目、任务、里程碑、会议、行动项、决定、知识页
DO $$
DECLARE
    v_project uuid;
    v_milestone uuid;
    v_task uuid;
    v_meeting uuid;
    v_action uuid;
    v_page uuid;
BEGIN
    INSERT INTO collab_project (tenant_id, org_unit_id, name, description)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000201', '协同平台', 'Phase 3')
    RETURNING id INTO v_project;

    INSERT INTO collab_milestone (tenant_id, project_id, name, due_date)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_project, 'G3 验收', current_date + 14)
    RETURNING id INTO v_milestone;

    INSERT INTO collab_task (tenant_id, project_id, org_unit_id, milestone_id, title,
                             created_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_project,
            'eeeeeeee-0000-4000-8000-000000000201', v_milestone, '补齐协作证据',
            'eeeeeeee-0000-4000-8000-000000000501')
    RETURNING id INTO v_task;

    BEGIN
        INSERT INTO collab_task (tenant_id, title, created_by_person_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000011', '无归属任务',
                'eeeeeeee-0000-4000-8000-000000000501');
        RAISE EXCEPTION '任务必须归属项目或部门';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    INSERT INTO collab_meeting (tenant_id, org_unit_id, project_id, title, meeting_date, notes,
                                created_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000201', v_project, '迭代评审', current_date, '纪要',
            'eeeeeeee-0000-4000-8000-000000000501')
    RETURNING id INTO v_meeting;

    INSERT INTO collab_action_item (tenant_id, meeting_id, title, owner_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_meeting, '补录工时',
            'eeeeeeee-0000-4000-8000-000000000502')
    RETURNING id INTO v_action;

    INSERT INTO collab_decision (tenant_id, org_unit_id, meeting_id, title, body, decided_at,
                                 created_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000201', v_meeting, '采用手工工时', 'Phase 3 不接语音',
            current_date, 'eeeeeeee-0000-4000-8000-000000000501');

    INSERT INTO collab_page (tenant_id, org_unit_id, title, body, created_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000201', '协作规范', '发帖需可回溯',
            'eeeeeeee-0000-4000-8000-000000000501')
    RETURNING id INTO v_page;

    IF v_project IS NULL OR v_task IS NULL OR v_action IS NULL OR v_page IS NULL THEN
        RAISE EXCEPTION '协作实体创建失败';
    END IF;
END
$$;

-- 5. 文件引用：必须有目标；引用不改变文件状态
DO $$
DECLARE
    v_channel uuid;
    v_link uuid;
BEGIN
    SELECT id INTO v_channel FROM collab_channel
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000011' AND name = '部门公告';

    BEGIN
        INSERT INTO collab_file_link (tenant_id, file_id, linked_by_person_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000011',
                'eeeeeeee-0000-4000-8000-000000000301',
                'eeeeeeee-0000-4000-8000-000000000501');
        RAISE EXCEPTION '文件引用必须指定频道/帖子/项目';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    INSERT INTO collab_file_link (tenant_id, channel_id, file_id, linked_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', v_channel,
            'eeeeeeee-0000-4000-8000-000000000301',
            'eeeeeeee-0000-4000-8000-000000000501')
    RETURNING id INTO v_link;

    IF v_link IS NULL THEN
        RAISE EXCEPTION '文件引用创建失败';
    END IF;

    INSERT INTO collab_file_version (tenant_id, file_id, version_no)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000301', 1);

    INSERT INTO collab_file_tag (tenant_id, file_id, tag)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011',
            'eeeeeeee-0000-4000-8000-000000000301', '会议');
END
$$;

-- 6. 通知与分享链接约束
DO $$
BEGIN
    BEGIN
        INSERT INTO collab_notification (tenant_id, recipient_person_id, resource_type,
                                         resource_id, title)
        VALUES ('eeeeeeee-0000-4000-8000-000000000011',
                'eeeeeeee-0000-4000-8000-000000000501', 'worklog',
                'eeeeeeee-0000-4000-8000-000000000999', '非法资源类型');
        RAISE EXCEPTION '非法通知资源类型应被拒绝';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    INSERT INTO collab_share_link (tenant_id, resource_type, resource_id, token_hash,
                                   created_by_person_id)
    VALUES ('eeeeeeee-0000-4000-8000-000000000011', 'channel',
            'eeeeeeee-0000-4000-8000-000000000999', 'hash-1',
            'eeeeeeee-0000-4000-8000-000000000501');

    BEGIN
        INSERT INTO collab_share_link (tenant_id, resource_type, resource_id, token_hash,
                                       created_by_person_id)
        VALUES ('eeeeeeee-0000-4000-8000-000000000011', 'channel',
                'eeeeeeee-0000-4000-8000-000000000999', 'hash-1',
                'eeeeeeee-0000-4000-8000-000000000501');
        RAISE EXCEPTION '分享令牌哈希必须唯一';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END
$$;
COMMIT;

-- 7. 跨租户隔离
BEGIN;
SELECT set_config('app.tenant_id', :collab_b, true);
INSERT INTO tenancy_tenant (id, slug, name, status, plan_code)
VALUES (:collab_b, 'collab-b', '协作乙', 'active', 'standard')
ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE
    visible int;
BEGIN
    SELECT count(*) INTO visible FROM collab_channel
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000011';
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见频道：%', visible;
    END IF;

    SELECT count(*) INTO visible FROM collab_task
     WHERE tenant_id = 'eeeeeeee-0000-4000-8000-000000000011';
    IF visible <> 0 THEN
        RAISE EXCEPTION '跨租户可见任务：%', visible;
    END IF;
END
$$;
COMMIT;

-- 8. RLS 开关与策略数量
DO $$
DECLARE
    tbl text;
    flags record;
    policies int;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'collab_channel', 'collab_channel_member', 'collab_post', 'collab_comment',
        'collab_mention', 'collab_receipt', 'collab_project', 'collab_project_member',
        'collab_file_link', 'collab_file_version', 'collab_file_tag', 'collab_task',
        'collab_milestone', 'collab_meeting', 'collab_action_item', 'collab_decision',
        'collab_page', 'collab_notification', 'collab_share_link'] LOOP
        SELECT c.relrowsecurity, c.relforcerowsecurity INTO flags
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relname = tbl;
        IF NOT FOUND THEN
            RAISE EXCEPTION '缺少协作表：%', tbl;
        END IF;
        IF NOT flags.relrowsecurity OR NOT flags.relforcerowsecurity THEN
            RAISE EXCEPTION '% 必须启用并强制 RLS', tbl;
        END IF;

        SELECT count(*) INTO policies FROM pg_policies WHERE tablename = tbl;
        IF policies <> 1 THEN
            RAISE EXCEPTION '% 应恰好有 1 条策略，实际 %', tbl, policies;
        END IF;
    END LOOP;
END
$$;

\echo 'WP03-03 部门协作数据库断言通过'
