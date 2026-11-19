-- V20：WP04-02 AI 周报自动总结——来源映射与周报快照同生命周期。
--
-- 设计要点：
--   1. worklog_weekly_report 已有触发器保证提交后内容冻结，但
--      worklog_report_source 仍可被改写，导致「数字可回溯」失效；
--   2. 新增触发器：父周报不是 draft 时，禁止对来源映射增删改；
--   3. 提交顺序必须是「先写来源、再置 submitted」，否则会被本触发器拦下；
--   4. 无表结构变更，也不新增租户表。

CREATE FUNCTION worklog_report_source_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    target_report uuid;
    parent_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_report := OLD.report_id;
    ELSE
        target_report := NEW.report_id;
    END IF;

    SELECT status INTO parent_status
      FROM worklog_weekly_report
     WHERE id = target_report;

    -- 父周报被级联删除时已不可见，此时放行由外键完成清理。
    IF parent_status IS NOT NULL AND parent_status <> 'draft' THEN
        RAISE EXCEPTION '已提交周报的来源映射不可修改';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION worklog_report_source_guard() IS
    '来源映射跟随周报状态：仅 draft 周报可写，提交后与快照一起冻结';

CREATE TRIGGER worklog_report_source_guard_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON worklog_report_source
    FOR EACH ROW EXECUTE FUNCTION worklog_report_source_guard();
