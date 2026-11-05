-- =============================================================================
-- V3 · 计划版本（W5 / dev-BD5）
-- 归属：M2-BD-03（FR-GT-005 基础：发布/回滚/自动冻结/快照）
-- 依据：docs/plan/m2-plan.md 第 4 节 V3 行、contract-freeze C24
-- 约束：只新增 plan_version 表与 task.plan_version_id 可空列；
--       不修改 V1 已冻结的 orders/task 既有列语义。
-- 回滚点 R3：task.plan_version_id 可空，未发布任务保持 NULL，旧任务行为不变；
--            发布/回滚端点下线即回到 M1 行为（M1 无计划版本概念）；
--            必要时先置空 task.plan_version_id、删除外键与索引，再 DROP TABLE plan_version。
-- =============================================================================

CREATE TABLE plan_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    sandbox_id      UUID         NOT NULL,
    -- 计划业务键：租户内唯一，M2 取 PLAN-YYYYMMDD-NNN（见 C24 版本号规则）
    plan_no         VARCHAR(64)  NOT NULL,
    -- 对外版本号：同场景内从 1 递增（应用层 nextVersionNo 生成）
    version_no      VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    -- 发布时的不可变任务快照（任务条、资源、时间、冻结态、降级标记等）
    snapshot        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    task_count      INTEGER      NOT NULL DEFAULT 0,
    published_at    TIMESTAMPTZ,
    published_by    UUID,
    -- 回滚来源：新版本记录其所回滚到的历史版本主键
    rollback_of     UUID,
    reason          TEXT,
    -- 发布审批人（C24 publish 的 approver），M2 仅记录不校验
    approver        VARCHAR(64),
    version         INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_plan_version_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_plan_version_sandbox FOREIGN KEY (sandbox_id) REFERENCES sandbox (id),
    CONSTRAINT fk_plan_version_rollback_of FOREIGN KEY (rollback_of) REFERENCES plan_version (id),
    CONSTRAINT chk_plan_version_status CHECK (status IN ('DRAFT', 'REVIEW', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_plan_version_task_count CHECK (task_count >= 0),
    CONSTRAINT chk_plan_version_version CHECK (version >= 1),
    CONSTRAINT chk_plan_version_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object')
);
CREATE UNIQUE INDEX uq_plan_version_tenant_plan_no ON plan_version (tenant_id, plan_no) WHERE deleted = false;
CREATE INDEX idx_plan_version_tenant_sandbox_status ON plan_version (tenant_id, sandbox_id, status) WHERE deleted = false;
CREATE INDEX idx_plan_version_published_at ON plan_version (tenant_id, published_at DESC) WHERE deleted = false;
COMMENT ON TABLE plan_version IS '计划版本；发布生成不可变 snapshot，回滚生成新版本并记录 rollback_of；已执行任务拒绝回滚（40903）';

-- task 只新增可空列，既有列语义不变；未发布任务保持 NULL
ALTER TABLE task ADD COLUMN plan_version_id UUID;
ALTER TABLE task ADD CONSTRAINT fk_task_plan_version FOREIGN KEY (plan_version_id) REFERENCES plan_version (id);
CREATE INDEX idx_task_plan_version ON task (plan_version_id);
COMMENT ON COLUMN task.plan_version_id IS '任务所属已发布计划版本；NULL 表示尚未发布（可空，旧数据不受影响）';
