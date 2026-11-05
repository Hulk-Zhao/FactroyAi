-- -----------------------------------------------------------------------------
-- V14：换型矩阵（V05-08，contract-freeze C44）
--
-- 号段说明：V13 已由 V05-01「Agent 基础框架」占用（见 V13__agent_framework.sql），
-- 本卡按补充发号顺延至 V14；V8–V10 仍为空号不可复用，V1–V13 已应用迁移一个字都不改。
--
-- FROM/TO 维度按产品（MATERIAL_CODE）或程序（OPERATION_CODE）成组；
-- 生效范围由 resource_id / resource_group_id 表达：两者均为空即租户级默认，
-- 仅 group 非空为资源组级，resource 非空为资源级。
-- setup_seconds 为默认换型秒数；same/cross_series_setup_seconds 为同系列/跨系列特例，
-- 为空表示回退 setup_seconds。
-- -----------------------------------------------------------------------------
CREATE TABLE setup_matrix_entry (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID         NOT NULL,
    key_type                    VARCHAR(32)  NOT NULL,
    from_key                    VARCHAR(64),
    to_key                      VARCHAR(64),
    scope_type                  VARCHAR(16)  NOT NULL DEFAULT 'TENANT',
    resource_id                 UUID,
    resource_group_id           UUID,
    setup_seconds               INTEGER      NOT NULL,
    same_series_setup_seconds   INTEGER,
    cross_series_setup_seconds  INTEGER,
    remark                      VARCHAR(255),
    version                     INTEGER      NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by                  UUID,
    updated_by                  UUID,
    deleted                     BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_setup_matrix_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_setup_matrix_resource FOREIGN KEY (resource_id) REFERENCES resource (id),
    CONSTRAINT fk_setup_matrix_resource_group FOREIGN KEY (resource_group_id) REFERENCES resource_group (id),
    CONSTRAINT chk_setup_matrix_key_type CHECK (key_type IN ('MATERIAL_CODE', 'OPERATION_CODE')),
    CONSTRAINT chk_setup_matrix_scope_type CHECK (scope_type IN ('TENANT', 'RESOURCE_GROUP', 'RESOURCE')),
    CONSTRAINT chk_setup_matrix_scope CHECK (
        (scope_type = 'TENANT' AND resource_id IS NULL AND resource_group_id IS NULL)
        OR (scope_type = 'RESOURCE_GROUP' AND resource_id IS NULL AND resource_group_id IS NOT NULL)
        OR (scope_type = 'RESOURCE' AND resource_id IS NOT NULL AND resource_group_id IS NULL)
    ),
    CONSTRAINT chk_setup_matrix_setup CHECK (setup_seconds >= 0),
    CONSTRAINT chk_setup_matrix_same_series CHECK (
        same_series_setup_seconds IS NULL OR same_series_setup_seconds >= 0
    ),
    CONSTRAINT chk_setup_matrix_cross_series CHECK (
        cross_series_setup_seconds IS NULL OR cross_series_setup_seconds >= 0
    ),
    CONSTRAINT chk_setup_matrix_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_setup_matrix_business_key ON setup_matrix_entry
    (tenant_id, key_type, from_key, to_key, scope_type, resource_id, resource_group_id)
    NULLS NOT DISTINCT
    WHERE deleted = false;
CREATE INDEX idx_setup_matrix_lookup ON setup_matrix_entry
    (tenant_id, key_type, scope_type) WHERE deleted = false;
COMMENT ON TABLE setup_matrix_entry IS '换型时间矩阵：默认值 + 同/跨系列特例，租户/资源组/资源三级生效范围（C44）';
COMMENT ON COLUMN setup_matrix_entry.from_key IS '前序任务维度键（产品物料编码或工序编码），空表示通配默认';
COMMENT ON COLUMN setup_matrix_entry.to_key IS '当前任务维度键，空表示通配默认';
COMMENT ON COLUMN setup_matrix_entry.scope_type IS 'TENANT / RESOURCE_GROUP / RESOURCE，与 resource_id/resource_group_id 一致';
COMMENT ON COLUMN setup_matrix_entry.setup_seconds IS '默认换型秒数；未配置同/跨系列特例时使用';
COMMENT ON COLUMN setup_matrix_entry.same_series_setup_seconds IS '同系列特例秒数，空表示回退默认';
COMMENT ON COLUMN setup_matrix_entry.cross_series_setup_seconds IS '跨系列特例秒数，空表示回退默认';
