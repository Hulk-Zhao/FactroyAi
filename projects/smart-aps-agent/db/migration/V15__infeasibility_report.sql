-- C51 求解不可行证据持久化（B2）。
--
-- report_json 保存 C51 完整形状（含 assumptions / narrative / 每类 <= 8 条证据）；
-- repair_candidates_json 保存 B1 产出的修复候选，供 C52 独立车道读取。
-- 纪律：NO_EVIDENCE 的 generated_at 必须为 NULL，禁止用 now() 兜底；
--       其余 verdict 必须带 generated_at。唯一键保证同一任务重复终态只保留一行（upsert）。
CREATE TABLE infeasibility_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    solve_task_id BIGINT NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    generated_at TIMESTAMPTZ NULL,
    candidate_seed BIGINT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    llm_status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
    narrative TEXT NULL,
    report_json JSONB NOT NULL,
    repair_candidates_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_infeasibility_report_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT fk_infeasibility_report_solve_task
        FOREIGN KEY (solve_task_id) REFERENCES solve_task (id) ON DELETE CASCADE,
    CONSTRAINT chk_infeasibility_report_verdict
        CHECK (verdict IN ('NO_FEASIBLE_SOLUTION_FOUND', 'NO_EVIDENCE', 'PARTIAL_EVIDENCE')),
    CONSTRAINT chk_infeasibility_report_llm_status
        CHECK (llm_status IN ('DISABLED', 'OK', 'FALLBACK')),
    CONSTRAINT chk_infeasibility_report_no_evidence_generated_at
        CHECK (verdict <> 'NO_EVIDENCE' OR generated_at IS NULL),
    CONSTRAINT chk_infeasibility_report_evidence_generated_at
        CHECK (verdict = 'NO_EVIDENCE' OR generated_at IS NOT NULL),
    CONSTRAINT chk_infeasibility_report_report_object
        CHECK (jsonb_typeof(report_json) = 'object'),
    CONSTRAINT chk_infeasibility_report_repairs_array
        CHECK (jsonb_typeof(repair_candidates_json) = 'array')
);

COMMENT ON TABLE infeasibility_report IS 'C51 求解不可行证据（B2 持久化）';
COMMENT ON COLUMN infeasibility_report.report_json IS 'C51 InfeasibilityReport 完整 JSON';
COMMENT ON COLUMN infeasibility_report.repair_candidates_json IS 'B1 RepairCandidate 列表 JSON';
COMMENT ON COLUMN infeasibility_report.generated_at IS '证据生成时间；NO_EVIDENCE 必须为 NULL';

CREATE UNIQUE INDEX uq_infeasibility_report_task
    ON infeasibility_report (tenant_id, solve_task_id);
CREATE INDEX idx_infeasibility_report_tenant_created
    ON infeasibility_report (tenant_id, created_at DESC);
