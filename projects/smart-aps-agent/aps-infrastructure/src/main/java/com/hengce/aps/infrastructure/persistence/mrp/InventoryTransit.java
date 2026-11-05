package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 在途库存持久化实体，字段对齐 V2 迁移的 {@code inventory_transit} 表。
 *
 * <p>状态为 {@code PLANNED / CONFIRMED / CANCELLED} 的 VARCHAR + CHECK；MRP 仅统计非
 * CANCELLED 的在途数量，按契约不按预计到货日裁剪。</p>
 */
@TableName(value = "inventory_transit", autoResultMap = true)
public class InventoryTransit extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 物料主键（material.id）。 */
    @TableField("material_id")
    private UUID materialId;

    /** 在途数量，必须大于 0。 */
    @TableField("qty")
    private BigDecimal qty;

    /** 预计到货日期。 */
    @TableField("expected_date")
    private LocalDate expectedDate;

    /** 来源类型 PURCHASE / TRANSFER / PRODUCTION。 */
    @TableField("source_type")
    private String sourceType;

    /** 状态 PLANNED / CONFIRMED / CANCELLED。 */
    @TableField("status")
    private String status;

    public UUID getMaterialId() {
        return materialId;
    }

    public void setMaterialId(UUID materialId) {
        this.materialId = materialId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public LocalDate getExpectedDate() {
        return expectedDate;
    }

    public void setExpectedDate(LocalDate expectedDate) {
        this.expectedDate = expectedDate;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
