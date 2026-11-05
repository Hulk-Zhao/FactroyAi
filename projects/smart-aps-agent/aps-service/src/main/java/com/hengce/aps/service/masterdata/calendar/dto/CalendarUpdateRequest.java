package com.hengce.aps.service.masterdata.calendar.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 日历更新请求，对齐 materials.yaml 的 CalendarUpdateRequest（C14）。
 *
 * <p>仅更新传入字段；calendarVersion 不可修改（新版本走创建接口）。
 * version 必填，用于乐观锁校验，冲突返回 40901/409。字段为 null 表示不更新。</p>
 *
 * @param version 当前乐观锁版本，必填
 * @param name 新名称，可空表示不变
 * @param type 新类型，可空表示不变
 * @param resourceId 新绑定资源，可空表示不变
 * @param resourceGroupId 新绑定资源组，可空表示不变
 * @param effectiveFrom 新生效起始日期，可空表示不变
 * @param effectiveTo 新生效结束日期，可空表示不变
 * @param shifts 新班次数组，可空表示不变
 * @param holidays 新节假日数组，可空表示不变
 */
public record CalendarUpdateRequest(
        @NotNull @Min(1) Integer version,
        @Size(max = 256) String name,
        @Pattern(regexp = "FACTORY|RESOURCE|SHIFT") String type,
        UUID resourceId,
        UUID resourceGroupId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Valid List<ShiftPayload> shifts,
        @Valid List<HolidayPayload> holidays) {
}
