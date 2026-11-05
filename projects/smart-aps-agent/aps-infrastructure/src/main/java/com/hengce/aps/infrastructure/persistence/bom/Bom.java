package com.hengce.aps.infrastructure.persistence.bom;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BOM 主表持久化实体，字段对齐 V1 迁移的 {@code bom} 表与 materials.yaml 的 Bom 模式。
 *
 * <p>{@code status} 以 VARCHAR + CHECK 落库（DRAFT/ACTIVE/INACTIVE），实体保持 String 以
 * 避免持久层依赖业务层枚举；API 层用 {@code BomStatus} 做精确筛选。</p>
 *
 * <p>{@code items} 为 JSONB 渲染快照，正式明细以 {@code bom_item} 为唯一事实来源，
 * 写入时由服务层同步刷新（schema.md 5.4）。主键沿用 {@link BaseEntity} 的
 * {@code IdType.INPUT}，UUID 由服务层显式生成。</p>
 */
@TableName(value = "bom", autoResultMap = true)
public class Bom extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 父件物料主键。 */
    @TableField("parent_material_id")
    private UUID parentMaterialId;

    /** 业务版本号（PRD 的 version）。 */
    @TableField("bom_version")
    private String bomVersion;

    /** 生效日期。 */
    @TableField("effective_date")
    private LocalDate effectiveDate;

    /** 失效日期，空表示长期有效。 */
    @TableField("expiry_date")
    private LocalDate expiryDate;

    /** 状态：DRAFT / ACTIVE / INACTIVE。 */
    @TableField("status")
    private String status;

    /** 是否拼板 BOM。 */
    @TableField("is_panel")
    private Boolean isPanel;

    /** 拼板数量。 */
    @TableField("panel_size")
    private Integer panelSize;

    /** 子件快照数组（JSONB），结构见 schema.md 5.4。 */
    @TableField(value = "items", typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> items = new ArrayList<>();

    public UUID getParentMaterialId() {
        return parentMaterialId;
    }

    public void setParentMaterialId(UUID parentMaterialId) {
        this.parentMaterialId = parentMaterialId;
    }

    public String getBomVersion() {
        return bomVersion;
    }

    public void setBomVersion(String bomVersion) {
        this.bomVersion = bomVersion;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getIsPanel() {
        return isPanel;
    }

    public void setIsPanel(Boolean isPanel) {
        this.isPanel = isPanel;
    }

    public Integer getPanelSize() {
        return panelSize;
    }

    public void setPanelSize(Integer panelSize) {
        this.panelSize = panelSize;
    }

    public List<Map<String, Object>> getItems() {
        return items;
    }

    public void setItems(List<Map<String, Object>> items) {
        this.items = items;
    }
}
