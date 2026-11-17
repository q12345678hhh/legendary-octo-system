-- V27 AI 工具评分人追踪。
--
-- 职责分离规则要求：评分人不得是登记人，启用人不得是登记人或评分人。
-- 这里补记评分人与评分时间，供接口把属性传给授权判定，不改变既有列。

ALTER TABLE ai_tool ADD COLUMN rated_by text;
ALTER TABLE ai_tool ADD COLUMN rated_at timestamptz;

COMMENT ON COLUMN ai_tool.rated_by IS '最近一次评分的主体（actor id），为空表示未评分';
COMMENT ON COLUMN ai_tool.rated_at IS '最近一次评分时间';
