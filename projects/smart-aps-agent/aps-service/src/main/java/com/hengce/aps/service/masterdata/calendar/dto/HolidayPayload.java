package com.hengce.aps.service.masterdata.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * 节假日定义，字段对齐 materials.yaml 的 Holiday。
 *
 * <p>kind 取值见 docs/api/enums.md：LEGAL（法定节假日）、COMPANY（公司假日）、
 * MAKEUP（调休上班日）。可用工时计算中 LEGAL/COMPANY 视为非工作日。</p>
 *
 * @param date 日期
 * @param name 名称
 * @param kind 类型
 */
public record HolidayPayload(
        @NotNull LocalDate date,
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "LEGAL|COMPANY|MAKEUP") String kind) {
}
