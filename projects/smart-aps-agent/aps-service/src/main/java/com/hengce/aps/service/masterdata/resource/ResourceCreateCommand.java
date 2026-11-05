package com.hengce.aps.service.masterdata.resource;

import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 创建资源命令。
 *
 * @param code 资源编码
 * @param name 资源名称
 * @param type 资源类型
 * @param groupId 资源组主键
 * @param capacityMode 产能模式
 * @param capacityValue 产能值，空按 DB 默认 0
 * @param batchCapacity 批量，BATCH_SECONDS 模式空按 1
 * @param efficiency 效率系数，空按 1.00
 * @param calendarId 绑定日历主键
 * @param setupSeconds 换型/准备工时（秒），空按 0
 * @param teardownSeconds 拆卸工时（秒），空按 0
 * @param industryAttrs 行业属性
 */
public record ResourceCreateCommand(
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
        Map<String, Object> industryAttrs) {
}
