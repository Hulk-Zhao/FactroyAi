-- -----------------------------------------------------------------------------
-- M2-SE-05：约束归因补记软约束权重与扣分
-- V1 的 constraint_justification 只有 score_impact（HARD/SOFT 文本），无法回答
-- “某条软约束扣了多少分”。本迁移新增两列，不改历史列语义：
--   weight    ：求解时该规则生效权重（硬约束为空）
--   deduction ：软约束扣分（weight × 违反量，正数表示被扣掉的软分；硬约束为 0）
-- -----------------------------------------------------------------------------
ALTER TABLE constraint_justification
    ADD COLUMN IF NOT EXISTS weight INTEGER,
    ADD COLUMN IF NOT EXISTS deduction NUMERIC(18, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN constraint_justification.weight
    IS '约束归因对应的软约束权重（RuleCode 在 constraint_id 中）；硬约束为空（M2-SE-05）';
COMMENT ON COLUMN constraint_justification.deduction
    IS '软约束扣分（weight × 违反量，正数；硬约束为 0）（M2-SE-05）';
