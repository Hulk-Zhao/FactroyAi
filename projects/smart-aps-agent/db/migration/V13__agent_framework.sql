-- =============================================================================
-- V13 · Agent 基础框架（V05-01 / contract-freeze C43）
-- 归属：V05-01（后端排产引擎组）；号段原为 W3/W4 条件流的空号，C36/C37 已证
--       不需要任何 DDL，经登记后改由 Agent 卡使用（见 contract-freeze §25.13 修订说明）。
-- 约束：只新增本文件的三张表；V1–V12 已应用迁移一个字都不改。
-- 幂等：agent_prompt_version 唯一 (tenant_id, prompt_key, version)，并仅允许一个 active；
--       agent_checkpoint 唯一 (run_id, step_no) 供 ON CONFLICT 幂等覆盖。
-- 回滚点 R13：DROP TABLE agent_checkpoint; DROP TABLE agent_run; DROP TABLE agent_prompt_version;
-- =============================================================================

CREATE TABLE agent_prompt_version (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    prompt_key          VARCHAR(64)  NOT NULL,
    version             INTEGER      NOT NULL,
    content             TEXT         NOT NULL,
    note                VARCHAR(256),
    active              BOOLEAN      NOT NULL DEFAULT false,
    created_by          UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_prompt_version_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_agent_prompt_version_version CHECK (version > 0),
    CONSTRAINT chk_agent_prompt_version_content CHECK (length(content) > 0)
);

CREATE UNIQUE INDEX uq_agent_prompt_version
    ON agent_prompt_version (tenant_id, prompt_key, version);
-- 同一租户同一 Prompt 键只允许一个生效版本，避免回滚/激活时的双 active
CREATE UNIQUE INDEX uq_agent_prompt_version_active
    ON agent_prompt_version (tenant_id, prompt_key) WHERE active;
CREATE INDEX idx_agent_prompt_version_key
    ON agent_prompt_version (tenant_id, prompt_key, created_at);

CREATE TABLE agent_run (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    prompt_key          VARCHAR(64)  NOT NULL,
    prompt_version      INTEGER,
    provider            VARCHAR(32),
    degradation_reason  VARCHAR(32),
    variables           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_completed_step INTEGER      NOT NULL DEFAULT 0,
    error_message       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_agent_run_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'DEGRADED', 'FAILED')),
    CONSTRAINT chk_agent_run_last_step CHECK (last_completed_step >= 0),
    CONSTRAINT chk_agent_run_variables_object CHECK (jsonb_typeof(variables) = 'object')
);

CREATE INDEX idx_agent_run_tenant_created ON agent_run (tenant_id, created_at DESC);
CREATE INDEX idx_agent_run_status ON agent_run (tenant_id, status);

CREATE TABLE agent_checkpoint (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    step_no             INTEGER      NOT NULL,
    step_name           VARCHAR(64)  NOT NULL,
    state               JSONB        NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_checkpoint_run FOREIGN KEY (run_id) REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_checkpoint_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_agent_checkpoint_step UNIQUE (run_id, step_no),
    CONSTRAINT chk_agent_checkpoint_step CHECK (step_no >= 0),
    CONSTRAINT chk_agent_checkpoint_state_object CHECK (jsonb_typeof(state) = 'object')
);

CREATE INDEX idx_agent_checkpoint_run ON agent_checkpoint (run_id, step_no);

COMMENT ON TABLE agent_prompt_version IS 'Prompt 模板版本；同键版本号单调递增，最多一个 active，支持回滚上一版';
COMMENT ON TABLE agent_run IS 'Agent 运行摘要；状态 RUNNING/COMPLETED/DEGRADED/FAILED，FAILED 可续跑';
COMMENT ON TABLE agent_checkpoint IS '步骤级检查点；state 为整份运行状态 JSON，续跑从最大 step_no 恢复';
