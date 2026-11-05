-- =============================================================================
-- mfg-aps-platform（衡策 APS 开源版）数据库迁移 V2
-- 归属：M2 W6 / dev-MRP（MRP 齐套流）
-- 目标库：PostgreSQL 15+
-- 依据：docs/plan/m2-plan.md 第 4 节 V2 号段、第 8.1 节库存落表决定（C25）
--
-- 说明：
--   1. 仅新增 inventory / inventory_transit 两张表，不修改 V1 既有表与既有列语义。
--   2. 唯一约束与普通索引均使用 WHERE deleted = false 的部分索引，与 V1 软删除口径一致。
--   3. 库存数量统一 NUMERIC(18,4)，不允许为负；安全库存不允许为负。
--   4. 回滚点 R2：先关闭 aps.mrp.inventory-enabled，再按依赖顺序 DROP 两张新增表即可，
--      不改 Flyway 历史、不改既有表。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 11.1 库存（按物料 + 仓库快照）
-- -----------------------------------------------------------------------------
CREATE TABLE inventory (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL,
    material_id     UUID          NOT NULL,
    warehouse_code  VARCHAR(64)   NOT NULL DEFAULT 'DEFAULT',
    on_hand_qty     NUMERIC(18,4) NOT NULL DEFAULT 0,
    safety_stock    NUMERIC(18,4) NOT NULL DEFAULT 0,
    snapshot_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         INTEGER       NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN       NOT NULL DEFAULT false,
    CONSTRAINT fk_inventory_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_inventory_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT chk_inventory_on_hand CHECK (on_hand_qty >= 0),
    CONSTRAINT chk_inventory_safety_stock CHECK (safety_stock >= 0),
    CONSTRAINT chk_inventory_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_inventory_tenant_material_warehouse
    ON inventory (tenant_id, material_id, warehouse_code) WHERE deleted = false;
CREATE INDEX idx_inventory_tenant_material ON inventory (tenant_id, material_id) WHERE deleted = false;
COMMENT ON TABLE inventory IS '库存快照；同一租户+物料+仓库仅一条有效记录，availableQty = onHand + transit - safetyStock';
COMMENT ON COLUMN inventory.snapshot_at IS '库存快照时间（UTC）；PUT /inventory 幂等更新时取请求值，缺省为当前时间';

-- -----------------------------------------------------------------------------
-- 11.2 在途库存（采购/调拨/生产入库）
-- -----------------------------------------------------------------------------
CREATE TABLE inventory_transit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL,
    material_id     UUID          NOT NULL,
    qty             NUMERIC(18,4) NOT NULL,
    expected_date   DATE          NOT NULL,
    source_type     VARCHAR(16)   NOT NULL DEFAULT 'PURCHASE',
    status          VARCHAR(16)   NOT NULL DEFAULT 'CONFIRMED',
    version         INTEGER       NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN       NOT NULL DEFAULT false,
    CONSTRAINT fk_inventory_transit_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_inventory_transit_material FOREIGN KEY (material_id) REFERENCES material (id),
    CONSTRAINT chk_inventory_transit_qty CHECK (qty > 0),
    CONSTRAINT chk_inventory_transit_source CHECK (source_type IN ('PURCHASE', 'TRANSFER', 'PRODUCTION')),
    CONSTRAINT chk_inventory_transit_status CHECK (status IN ('PLANNED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_inventory_transit_version CHECK (version >= 1)
);
CREATE INDEX idx_inventory_transit_tenant_material_date
    ON inventory_transit (tenant_id, material_id, expected_date) WHERE deleted = false;
COMMENT ON TABLE inventory_transit IS '在途库存；MRP 可用量按 onHand + transit - safetyStock 计算，CANCELLED 不计入';
COMMENT ON COLUMN inventory_transit.expected_date IS '预计到货日期；M2 按契约计入全部非 CANCELLED 在途，不按到期日裁剪';
