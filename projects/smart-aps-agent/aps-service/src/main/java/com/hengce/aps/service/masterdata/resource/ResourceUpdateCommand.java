package com.hengce.aps.service.masterdata.resource;

import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 更新资源命令；空字段表示保持不变，{@code code} 不可修改。
 *
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
 * @param industryAttrs 行业属性
 * @param version 乐观锁版本，必填
 */
public record ResourceUpdateCommand(
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
        Map<String, Object> industryAttrs,
        Integer version) {
}
