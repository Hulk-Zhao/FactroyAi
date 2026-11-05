package com.hengce.aps.service.masterdata.calendar.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 日历创建请求，对齐 materials.yaml 的 CalendarCreateRequest（C14）。
 *
 * <p>calendarVersion 为 INTEGER 业务版本号；同一租户同一 name 下新建版本时
 * 取当前最大 calendar_version + 1，历史版本只读。新版本通过本接口创建。</p>
 *
 * @param name 日历名称
 * @param calendarVersion 业务版本号，须等于同名 max+1
 * @param type 类型：FACTORY / RESOURCE / SHIFT
 * @param resourceId RESOURCE 类型绑定的资源
 * @param resourceGroupId 资源组级日历绑定的资源组
 * @param effectiveFrom 生效起始日期
 * @param effectiveTo 生效结束日期，空表示长期有效
 * @param shifts 班次数组
 * @param holidays 节假日数组
 */
public record CalendarCreateRequest(
        @NotBlank @Size(max = 256) String name,
        @NotNull @Min(1) Integer calendarVersion,
        @NotBlank @Pattern(regexp = "FACTORY|RESOURCE|SHIFT") String type,
        UUID resourceId,
        UUID resourceGroupId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @NotEmpty @Valid List<ShiftPayload> shifts,
        @Valid List<HolidayPayload> holidays) {
}
