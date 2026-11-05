-- =====================================================================
-- V4：约束 DSL 版本表（W2 / dev-SE1，M2-SE-02）
-- 契约：DSL 仅允许白名单 rule_code + 参数；YAML safe load；序列化永远输出 constraints 数组
-- 状态机：DRAFT（草稿）→ ACTIVE（唯一激活）→ ROLLBACK（已被回滚/替换）
-- =====================================================================

CREATE TABLE constraint_dsl (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    code            VARCHAR(128) NOT NULL,
    version_no      INTEGER     NOT NULL,
    yaml_text       TEXT        NOT NULL,
    compiled_spec   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    checksum        VARCHAR(64) NOT NULL,
    rollback_of     UUID,
    effective_from  TIMESTAMPTZ,
    version         INTEGER     NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_constraint_dsl_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_constraint_dsl_rollback FOREIGN KEY (rollback_of) REFERENCES constraint_dsl (id),
    CONSTRAINT chk_constraint_dsl_version CHECK (version_no >= 1),
    CONSTRAINT chk_constraint_dsl_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ROLLBACK')),
    CONSTRAINT chk_constraint_dsl_compiled_array CHECK (jsonb_typeof(compiled_spec) = 'array')
);

-- 同一租户、同一标识内版本号唯一
CREATE UNIQUE INDEX uq_constraint_dsl_tenant_code_version
    ON constraint_dsl (tenant_id, code, version_no) WHERE deleted = false;

-- 同一租户、同一标识只允许一个激活版本
CREATE UNIQUE INDEX uq_constraint_dsl_tenant_code_active
    ON constraint_dsl (tenant_id, code) WHERE status = 'ACTIVE' AND deleted = false;

CREATE INDEX idx_constraint_dsl_tenant_code_status
    ON constraint_dsl (tenant_id, code, status) WHERE deleted = false;

COMMENT ON TABLE constraint_dsl IS '约束 DSL 版本；code 为 DSL 标识，version_no 为版本号，同标识仅一个 ACTIVE';
COMMENT ON COLUMN constraint_dsl.yaml_text IS '规范化 YAML，规则以 constraints 数组表达';
COMMENT ON COLUMN constraint_dsl.compiled_spec IS '编译后的软约束快照数组，元素含 code/type/weight/hard/phase/params';
COMMENT ON COLUMN constraint_dsl.status IS 'DRAFT 草稿 / ACTIVE 激活 / ROLLBACK 已回滚';
COMMENT ON COLUMN constraint_dsl.checksum IS '规范化 YAML 的 SHA-256';
COMMENT ON COLUMN constraint_dsl.rollback_of IS '回滚来源版本主键';
COMMENT ON COLUMN constraint_dsl.effective_from IS '激活生效时刻（UTC）';
