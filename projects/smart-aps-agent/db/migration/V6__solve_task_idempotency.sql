-- -----------------------------------------------------------------------------
-- M2-SE-03：solve_task 场景级幂等唯一索引
-- V1 已有 uq_solve_task_idempotency (tenant_id, idempotency_key)，本迁移按 M2 计划
-- 补充 (tenant_id, sandbox_id, idempotency_key) 场景级索引；两个索引同时生效，
-- V1 的租户级约束更严格，因此不会放宽已有幂等语义。
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_solve_task_sandbox_idempotency
    ON solve_task (tenant_id, sandbox_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted = false;

COMMENT ON INDEX uq_solve_task_sandbox_idempotency
    IS 'solve_task 场景级幂等键唯一索引（M2-SE-03）';
