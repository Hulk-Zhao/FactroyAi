package com.hengce.aps.infrastructure.persistence.material;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 物料持久化实体，字段对齐 V1 迁移的 material 表与 materials.yaml 的 Material 模式。
 *
 * <p>放在 infrastructure 而非 domain：ext_attrs / industry_attrs 需要 {@link JsonbTypeHandler}
 * 作为 {@code @TableField#typeHandler}，该类型在 infrastructure 中，domain 依赖 infrastructure 会形成循环依赖。
 * 领域层仅保留 {@link MaterialType} 枚举。</p>
 *
 * <p>主键沿用 BaseEntity 的 {@code IdType.INPUT}；UUID 由服务层显式生成。</p>
 */
@TableName(value = "material", autoResultMap = true)
public class Material extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 物料业务编码，全局唯一（deleted=false 范围）。 */
    @TableField("code")
    private String code;

    /** 物料名称。 */
    @TableField("name")
    private String name;

    /** 物料类型。 */
    @TableField("type")
    private MaterialType type;

    /** 规格型号。 */
    @TableField("spec")
    private String spec;

    /** 基本单位。 */
    @TableField("unit")
    private String unit;

    /** 物料分类主键。 */
    @TableField("category_id")
    private UUID categoryId;

    /** 安全库存。 */
    @TableField("safety_stock")
    private BigDecimal safetyStock;

    /** 最小起订量。 */
    @TableField("moq")
    private BigDecimal moq;

    /** 采购提前期（天）。 */
    @TableField("lead_time_days")
    private Integer leadTimeDays;

    /** 默认供应商主键。 */
    @TableField("default_supplier_id")
    private UUID defaultSupplierId;

    /** 扩展属性（JSONB）。 */
    @TableField(value = "ext_attrs", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> extAttrs = new LinkedHashMap<>();

    /** 行业属性（JSONB）。 */
    @TableField(value = "industry_attrs", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> industryAttrs = new LinkedHashMap<>();

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MaterialType getType() {
        return type;
    }

    public void setType(MaterialType type) {
        this.type = type;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public BigDecimal getSafetyStock() {
        return safetyStock;
    }

    public void setSafetyStock(BigDecimal safetyStock) {
        this.safetyStock = safetyStock;
    }

    public BigDecimal getMoq() {
        return moq;
    }

    public void setMoq(BigDecimal moq) {
        this.moq = moq;
    }

    public Integer getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(Integer leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public UUID getDefaultSupplierId() {
        return defaultSupplierId;
    }

    public void setDefaultSupplierId(UUID defaultSupplierId) {
        this.defaultSupplierId = defaultSupplierId;
    }

    public Map<String, Object> getExtAttrs() {
        return extAttrs;
    }

    public void setExtAttrs(Map<String, Object> extAttrs) {
        this.extAttrs = extAttrs;
    }

    public Map<String, Object> getIndustryAttrs() {
        return industryAttrs;
    }

    public void setIndustryAttrs(Map<String, Object> industryAttrs) {
        this.industryAttrs = industryAttrs;
    }
}
