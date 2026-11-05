package com.hengce.aps.infrastructure.persistence.resource;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 资源持久化实体，字段对齐 V1 迁移的 resource 表与 materials.yaml 的 Resource 模式。
 *
 * <p>主键沿用 BaseEntity 的 {@code IdType.INPUT}；UUID 由服务层显式生成。
 * maintenance_plan / industry_attrs 为 JSONB，需要 {@link JsonbTypeHandler}。</p>
 *
 * <p>M1 不引入 status 字段：停用统一通过 {@code deleted=true} 软删除表达（C14）。</p>
 */
@TableName(value = "resource", autoResultMap = true)
public class Resource extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 资源业务编码，同一租户内未删除记录唯一。 */
    @TableField("code")
    private String code;

    /** 资源名称。 */
    @TableField("name")
    private String name;

    /** 资源类型。 */
    @TableField("type")
    private ResourceType type;

    /** 资源组主键。 */
    @TableField("group_id")
    private UUID groupId;

    /** 产能模式。 */
    @TableField("capacity_mode")
    private CapacityMode capacityMode;

    /** 产能值，单位随产能模式变化。 */
    @TableField("capacity_value")
    private BigDecimal capacityValue;

    /** 批量，BATCH_SECONDS 模式必填（C2 空按 1 兜底）。 */
    @TableField("batch_capacity")
    private Integer batchCapacity;

    /** 效率系数，范围 0.5–1.5，空按 1.00。 */
    @TableField("efficiency")
    private BigDecimal efficiency;

    /** 绑定的工作日历主键。 */
    @TableField("calendar_id")
    private UUID calendarId;

    /** 换型/准备工时（秒）。 */
    @TableField("setup_seconds")
    private Integer setupSeconds;

    /** 拆卸工时（秒）。 */
    @TableField("teardown_seconds")
    private Integer teardownSeconds;

    /** 维护计划（JSONB）。 */
    @TableField(value = "maintenance_plan", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> maintenancePlan = new LinkedHashMap<>();

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

    public ResourceType getType() {
        return type;
    }

    public void setType(ResourceType type) {
        this.type = type;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public CapacityMode getCapacityMode() {
        return capacityMode;
    }

    public void setCapacityMode(CapacityMode capacityMode) {
        this.capacityMode = capacityMode;
    }

    public BigDecimal getCapacityValue() {
        return capacityValue;
    }

    public void setCapacityValue(BigDecimal capacityValue) {
        this.capacityValue = capacityValue;
    }

    public Integer getBatchCapacity() {
        return batchCapacity;
    }

    public void setBatchCapacity(Integer batchCapacity) {
        this.batchCapacity = batchCapacity;
    }

    public BigDecimal getEfficiency() {
        return efficiency;
    }

    public void setEfficiency(BigDecimal efficiency) {
        this.efficiency = efficiency;
    }

    public UUID getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(UUID calendarId) {
        this.calendarId = calendarId;
    }

    public Integer getSetupSeconds() {
        return setupSeconds;
    }

    public void setSetupSeconds(Integer setupSeconds) {
        this.setupSeconds = setupSeconds;
    }

    public Integer getTeardownSeconds() {
        return teardownSeconds;
    }

    public void setTeardownSeconds(Integer teardownSeconds) {
        this.teardownSeconds = teardownSeconds;
    }

    public Map<String, Object> getMaintenancePlan() {
        return maintenancePlan;
    }

    public void setMaintenancePlan(Map<String, Object> maintenancePlan) {
        this.maintenancePlan = maintenancePlan;
    }

    public Map<String, Object> getIndustryAttrs() {
        return industryAttrs;
    }

    public void setIndustryAttrs(Map<String, Object> industryAttrs) {
        this.industryAttrs = industryAttrs;
    }
}
