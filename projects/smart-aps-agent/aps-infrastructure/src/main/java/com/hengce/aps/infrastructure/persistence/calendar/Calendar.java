package com.hengce.aps.infrastructure.persistence.calendar;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作日历实体，对应 V1 迁移的 {@code calendar} 表。
 *
 * <p>shifts / holidays 为 JSONB 数组，元素结构见 docs/db/schema.md 5.6：
 * shifts 元素为 {@code {startTime,endTime,daysOfWeek}}；holidays 元素为
 * {@code {date,name,kind}}。JSONB 读写由基础设施层 Mapper 显式绑定
 * {@code JsonbTypeHandler}。</p>
 */
@TableName("calendar")
public class Calendar extends BaseEntity {

    /**
     * 日历名称，必填；与 calendarVersion 组合在租户内唯一。
     */
    @TableField("name")
    private String name;

    /**
     * 日历类型：FACTORY / RESOURCE / SHIFT。
     */
    @TableField("type")
    private String type;

    /**
     * RESOURCE 类型日历绑定的资源。
     */
    @TableField("resource_id")
    private UUID resourceId;

    /**
     * 资源组级日历绑定的资源组。
     */
    @TableField("resource_group_id")
    private UUID resourceGroupId;

    /**
     * 生效起始日期（DATE）。
     */
    @TableField("effective_from")
    private LocalDate effectiveFrom;

    /**
     * 生效结束日期（DATE），可空表示长期有效。
     */
    @TableField("effective_to")
    private LocalDate effectiveTo;

    /**
     * 班次 JSONB 数组。
     */
    @TableField("shifts")
    private List<Map<String, Object>> shifts;

    /**
     * 节假日 JSONB 数组。
     */
    @TableField("holidays")
    private List<Map<String, Object>> holidays;

    /**
     * 日历业务版本号；历史版本只读。
     */
    @TableField("calendar_version")
    private Integer calendarVersion;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public void setResourceId(UUID resourceId) {
        this.resourceId = resourceId;
    }

    public UUID getResourceGroupId() {
        return resourceGroupId;
    }

    public void setResourceGroupId(UUID resourceGroupId) {
        this.resourceGroupId = resourceGroupId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public List<Map<String, Object>> getShifts() {
        return shifts;
    }

    public void setShifts(List<Map<String, Object>> shifts) {
        this.shifts = shifts;
    }

    public List<Map<String, Object>> getHolidays() {
        return holidays;
    }

    public void setHolidays(List<Map<String, Object>> holidays) {
        this.holidays = holidays;
    }

    public Integer getCalendarVersion() {
        return calendarVersion;
    }

    public void setCalendarVersion(Integer calendarVersion) {
        this.calendarVersion = calendarVersion;
    }
}
