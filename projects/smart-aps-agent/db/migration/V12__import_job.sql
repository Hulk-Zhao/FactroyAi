-- =============================================================================
-- V12 · 异步导入任务 import_job（W9 / C39）
-- 归属：M3-W9（contract-freeze §25.10 / §25.13 号段台账）
-- 约束：只新增 import_job 表；不改 V1–V11 任何对象。
-- 幂等：唯一 (tenant_id, entity, content_hash) WHERE status IN ('PENDING','RUNNING')，
--       同一内容在途只允许一个任务；终态后允许同内容重提。
-- 状态机：PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED；FAILED 必 committed=false。
-- 回滚点 R12：DROP TABLE import_job 即可回到无异步任务状态（不触及其他表）。
-- =============================================================================

CREATE TABLE import_job (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    entity              VARCHAR(32) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    content_hash        VARCHAR(80) NOT NULL,
    file_name           VARCHAR(256),
    file_bytes          BIGINT      NOT NULL DEFAULT 0,
    total_rows          INTEGER     NOT NULL DEFAULT 0,
    processed_rows      INTEGER     NOT NULL DEFAULT 0,
    inserted            INTEGER     NOT NULL DEFAULT 0,
    updated             INTEGER     NOT NULL DEFAULT 0,
    skipped             INTEGER     NOT NULL DEFAULT 0,
    invalid_rows        INTEGER     NOT NULL DEFAULT 0,
    errors              JSONB       NOT NULL DEFAULT '[]'::jsonb,
    committed           BOOLEAN     NOT NULL DEFAULT false,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    CONSTRAINT fk_import_job_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_import_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_import_job_errors_array CHECK (jsonb_typeof(errors) = 'array'),
    CONSTRAINT chk_import_job_errors_limit CHECK (jsonb_array_length(errors) <= 200),
    CONSTRAINT chk_import_job_file_bytes CHECK (file_bytes >= 0),
    CONSTRAINT chk_import_job_counters CHECK (total_rows >= 0 AND processed_rows >= 0 AND inserted >= 0
        AND updated >= 0 AND skipped >= 0 AND invalid_rows >= 0)
);

CREATE UNIQUE INDEX uq_import_job_inflight
    ON import_job (tenant_id, entity, content_hash) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_import_job_tenant_created
    ON import_job (tenant_id, created_at);

COMMENT ON TABLE import_job IS '异步导入任务；单实例并发 2，终态保留 24h，错误行最多 200 条';
