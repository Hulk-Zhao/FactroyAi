-- M2 W4 增量重排与甘特后端：task 锁定/冻结列（C21 / 计划第 4 节 V5）
-- 原则：只新增列与索引，不改 task 既有列语义；默认 NONE 保证旧任务行为不变。
-- 回滚点 R5：关闭 aps.schedule.lock-enabled 后退化为冻结布尔语义；lock_type 默认 NONE。

ALTER TABLE task
    ADD COLUMN lock_type           VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN locked_from         TIMESTAMPTZ,
    ADD COLUMN locked_to           TIMESTAMPTZ,
    ADD COLUMN locked_resource_ids JSONB       NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE task
    ADD CONSTRAINT chk_task_lock_type CHECK (lock_type IN ('NONE', 'TIME_WINDOW', 'RESOURCE', 'FROZEN')),
    ADD CONSTRAINT chk_task_lock_window
        CHECK (locked_from IS NULL OR locked_to IS NULL OR locked_to > locked_from),
    ADD CONSTRAINT chk_task_locked_resources_array
        CHECK (jsonb_typeof(locked_resource_ids) = 'array');

-- 增量重排按场景筛选锁定任务；NONE 不进入部分索引，控制索引体积。
CREATE INDEX idx_task_sandbox_lock_type ON task (sandbox_id, lock_type)
    WHERE lock_type <> 'NONE' AND deleted = false;

COMMENT ON COLUMN task.lock_type IS '锁定类型：NONE/TIME_WINDOW/RESOURCE/FROZEN；与 status 的 FROZEN 解耦';
COMMENT ON COLUMN task.locked_from IS '时间窗锁定开始（含）；TIME_WINDOW 时必填';
COMMENT ON COLUMN task.locked_to IS '时间窗锁定结束（不含）；TIME_WINDOW 时必填';
COMMENT ON COLUMN task.locked_resource_ids IS '资源锁定集合；RESOURCE 时记录允许/锁定的资源主键';
