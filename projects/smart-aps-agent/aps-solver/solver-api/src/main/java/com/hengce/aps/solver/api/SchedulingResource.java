package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 资源快照；字段与 {@code resource} 表列一一对应，并补充 C12 §17.1 的可用时段。
 *
 * @param resourceId 资源主键
 * @param code 资源编码
 * @param type 资源类型：PRIMARY / SECONDARY / HUMAN / TOOL
 * @param groupId 资源组标识，可空
 * @param capacityMode 产能模式：UNIT_PER_HOUR / SECONDS_PER_UNIT / BATCH_SECONDS
 * @param capacityValue 产能值，单位随 {@code capacityMode} 变化
 * @param batchCapacity 批量容量，可空；C2 按 1 兜底
 * @param efficiency 效率系数，可空按 1.00
 * @param calendarId 日历标识，可空
 * @param status 资源状态：ACTIVE / INACTIVE
 * @param availabilityWindows 由日历展开的可用时段，空列表表示 7×24（C12 §17.1）
 */
public record SchedulingResource(
        UUID resourceId,
        String code,
        String type,
        UUID groupId,
        String capacityMode,
        BigDecimal capacityValue,
        Integer batchCapacity,
        BigDecimal efficiency,
        UUID calendarId,
        String status,
        List<AvailabilityWindow> availabilityWindows
) {

    /**
     * 归一化可用时段列表。
     */
    public SchedulingResource {
        availabilityWindows = List.copyOf(
                availabilityWindows == null ? List.of() : availabilityWindows);
    }

    /**
     * 兼容 C12 之前的调用方，等价于「无可用时段（7×24）」。
     *
     * @param resourceId 资源主键
     * @param code 资源编码
     * @param type 资源类型
     * @param groupId 资源组标识
     * @param capacityMode 产能模式
     * @param capacityValue 产能值
     * @param batchCapacity 批量容量
     * @param efficiency 效率系数
     * @param calendarId 日历标识
     * @param status 资源状态
     */
    public SchedulingResource(
            UUID resourceId,
            String code,
            String type,
            UUID groupId,
            String capacityMode,
            BigDecimal capacityValue,
            Integer batchCapacity,
            BigDecimal efficiency,
            UUID calendarId,
            String status) {
        this(resourceId, code, type, groupId, capacityMode, capacityValue, batchCapacity, efficiency,
                calendarId, status, List.of());
    }
}
