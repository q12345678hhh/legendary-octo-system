-- V28 新登记工具默认停用。
--
-- 登记只是提交元数据，必须经过他人评分后才能启用，因此默认状态改为 disabled；
-- 与职责分离规则（启用人不得是登记人或评分人）保持一致。

ALTER TABLE ai_tool ALTER COLUMN status SET DEFAULT 'disabled';
