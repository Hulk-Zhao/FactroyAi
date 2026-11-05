package com.hengce.aps.service.masterdata.calendar.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 日历出参，字段严格对齐 materials.yaml 的 Calendar（C14）。
 *
 * @param id 主键
 * @param name 名称
 * @param calendarVersion 业务版本号（INTEGER）
 * @param type 类型
 * @param resourceId 绑定资源
 * @param resourceGroupId 绑定资源组
 * @param effectiveFrom 生效起始日期
 * @param effectiveTo 生效结束日期，空表示长期有效
 * @param timezone IANA 时区名，取服务端配置 {@code aps.calendar.timezone}（默认 Asia/Shanghai）
 * @param shifts 班次数组
 * @param holidays 节假日数组
 * @param version 乐观锁版本
 */
public record CalendarResponse(
        UUID id,
        String name,
        Integer calendarVersion,
        String type,
        UUID resourceId,
        UUID resourceGroupId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String timezone,
        List<Map<String, Object>> shifts,
        List<Map<String, Object>> holidays,
        Integer version) {
}
