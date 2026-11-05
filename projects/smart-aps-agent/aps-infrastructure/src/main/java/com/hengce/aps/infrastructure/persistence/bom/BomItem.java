package com.hengce.aps.infrastructure.persistence.bom;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BOM 子件行持久化实体，字段对齐 V1 迁移的 {@code bom_item} 表。
 *
 * <p>{@code bom_item} 是 BOM 明细的唯一事实来源，{@code bom.items} 仅为渲染快照；
 * {@code substitutes} 为替代料 JSONB 数组，元素结构 {@code {materialId, materialCode, priority, ratio}}。</p>
 */
@TableName(value = "bom_item", autoResultMap = true)
public class BomItem extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 所属 BOM 主键。 */
    @TableField("bom_id")
    private UUID bomId;

    /** 子件物料主键。 */
    @TableField("child_material_id")
    private UUID childMaterialId;

    /** 单件用量，NUMERIC(18,6)，必须大于 0。 */
    @TableField("qty_per_unit")
    private BigDecimal qtyPerUnit;

    /** 损耗率（百分比 0–100）。 */
    @TableField("scrap_rate")
    private BigDecimal scrapRate;

    /** SMT 位号。 */
    @TableField("position")
    private String position;

    /** 替代料数组（JSONB）。 */
    @TableField(value = "substitutes", typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> substitutes = new ArrayList<>();

    /** 是否为虚拟件。 */
    @TableField("is_phantom")
    private Boolean isPhantom;

    /** BOM 层级。 */
    @TableField("level")
    private Integer level;

    /** 子件行序号。 */
    @TableField("seq_no")
    private Integer seqNo;

    public UUID getBomId() {
        return bomId;
    }

    public void setBomId(UUID bomId) {
        this.bomId = bomId;
    }

    public UUID getChildMaterialId() {
        return childMaterialId;
    }

    public void setChildMaterialId(UUID childMaterialId) {
        this.childMaterialId = childMaterialId;
    }

    public BigDecimal getQtyPerUnit() {
        return qtyPerUnit;
    }

    public void setQtyPerUnit(BigDecimal qtyPerUnit) {
        this.qtyPerUnit = qtyPerUnit;
    }

    public BigDecimal getScrapRate() {
        return scrapRate;
    }

    public void setScrapRate(BigDecimal scrapRate) {
        this.scrapRate = scrapRate;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public List<Map<String, Object>> getSubstitutes() {
        return substitutes;
    }

    public void setSubstitutes(List<Map<String, Object>> substitutes) {
        this.substitutes = substitutes;
    }

    public Boolean getIsPhantom() {
        return isPhantom;
    }

    public void setIsPhantom(Boolean isPhantom) {
        this.isPhantom = isPhantom;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }
}
