package com.hengce.aps.solver.api;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 资源可用时段（contract-freeze C12 §17.1）：UTC 半开区间 {@code [start, end)}。
 *
 * <p>{@code end} 必须晚于 {@code start}；同一资源的窗口按 {@code start} 升序且互不重叠，
 * 空列表表示 7×24 全可用。</p>
 *
 * @param start 可用开始时间（含）
 * @param end 可用结束时间（不含）
 */
public record AvailabilityWindow(
        OffsetDateTime start,
        OffsetDateTime end
) {

    /**
     * 校验区间合法性。
     */
    public AvailabilityWindow {
        Objects.requireNonNull(start, "可用时段的 start 不能为空");
        Objects.requireNonNull(end, "可用时段的 end 不能为空");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("可用时段的 end 必须晚于 start: " + start + " -> " + end);
        }
    }
}
