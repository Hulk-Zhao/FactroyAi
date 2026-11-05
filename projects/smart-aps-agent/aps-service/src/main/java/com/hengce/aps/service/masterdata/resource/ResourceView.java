package com.hengce.aps.service.masterdata.resource;

import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 资源返回视图，字段与 materials.yaml 的 Resource 模式一致。
 *
 * <p>C14：响应暴露只读 {@code id}（UUID），供 {@code calendar.resourceId} 等引用绑定；
 * M1 不返回 {@code status}，停用语义由软删除表达。</p>
 *
 * @param id 资源主键 UUID（只读）
 * @param code 资源编码
 * @param name 资源名称
 * @param type 资源类型
 * @param groupId 资源组主键
 * @param capacityMode 产能模式
 * @param capacityValue 产能值
 * @param batchCapacity 批量
 * @param efficiency 效率系数
 * @param calendarId 绑定日历主键
 * @param setupSeconds 换型/准备工时（秒）
 * @param teardownSeconds 拆卸工时（秒）
 * @param maintenancePlan 维护计划
 * @param industryAttrs 行业属性
 * @param version 乐观锁版本
 */
public record ResourceView(
        UUID id,
        String code,
        String name,
        ResourceType type,
        UUID groupId,
        CapacityMode capacityMode,
        BigDecimal capacityValue,
        Integer batchCapacity,
        BigDecimal efficiency,
        UUID calendarId,
        Integer setupSeconds,
        Integer teardownSeconds,
        Map<String, Object> maintenancePlan,
        Map<String, Object> industryAttrs,
        Integer version) {

    /**
     * 由持久化实体转换。
     *
     * @param resource 资源实体
     * @return 返回视图
     */
    public static ResourceView from(Resource resource) {
        return new ResourceView(
                (UUID) resource.getId(),
                resource.getCode(),
                resource.getName(),
                resource.getType(),
                resource.getGroupId(),
                resource.getCapacityMode(),
                resource.getCapacityValue(),
                resource.getBatchCapacity(),
                resource.getEfficiency(),
                resource.getCalendarId(),
                resource.getSetupSeconds(),
                resource.getTeardownSeconds(),
                resource.getMaintenancePlan(),
                resource.getIndustryAttrs(),
                resource.getVersion());
    }
}
