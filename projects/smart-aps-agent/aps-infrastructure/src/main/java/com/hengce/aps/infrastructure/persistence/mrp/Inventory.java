package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 库存快照持久化实体，字段对齐 V2 迁移的 {@code inventory} 表。
 *
 * <p>同一租户 + 物料 + 仓库由部分唯一索引 {@code uq_inventory_tenant_material_warehouse}
 * 保证只有一条有效记录；{@code version} 为乐观锁版本，PUT 并发更新冲突返回 40901。</p>
 */
@TableName(value = "inventory", autoResultMap = true)
public class Inventory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 物料主键（material.id）。 */
    @TableField("material_id")
    private UUID materialId;

    /** 仓库编码，默认 DEFAULT。 */
    @TableField("warehouse_code")
    private String warehouseCode;

    /** 现有库存数量，不允许为负。 */
    @TableField("on_hand_qty")
    private BigDecimal onHandQty;

    /** 安全库存，不允许为负。 */
    @TableField("safety_stock")
    private BigDecimal safetyStock;

    /** 快照时间（UTC）。 */
    @TableField("snapshot_at")
    private OffsetDateTime snapshotAt;

    public UUID getMaterialId() {
        return materialId;
    }

    public void setMaterialId(UUID materialId) {
        this.materialId = materialId;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public void setWarehouseCode(String warehouseCode) {
        this.warehouseCode = warehouseCode;
    }

    public BigDecimal getOnHandQty() {
        return onHandQty;
    }

    public void setOnHandQty(BigDecimal onHandQty) {
        this.onHandQty = onHandQty;
    }

    public BigDecimal getSafetyStock() {
        return safetyStock;
    }

    public void setSafetyStock(BigDecimal safetyStock) {
        this.safetyStock = safetyStock;
    }

    public OffsetDateTime getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(OffsetDateTime snapshotAt) {
        this.snapshotAt = snapshotAt;
    }
}
