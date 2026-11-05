package com.hengce.aps.service.masterdata.calendar.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 班次定义，字段对齐 materials.yaml 的 Shift。
 *
 * <p>时间格式为 {@code HH:mm}，结束时间允许 {@code 24:00} 表示当日结束；
 * 结束早于开始表示跨夜班次（如 20:00-06:00）。</p>
 *
 * @param startTime 班次开始时间
 * @param endTime 班次结束时间
 * @param daysOfWeek 生效星期（1=周一 … 7=周日）
 */
public record ShiftPayload(
        @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String startTime,
        @NotBlank @Pattern(regexp = "(24:00|([01]\\d|2[0-3]):[0-5]\\d)") String endTime,
        @NotEmpty List<@NotNull @Min(1) @Max(7) Integer> daysOfWeek) {
}
