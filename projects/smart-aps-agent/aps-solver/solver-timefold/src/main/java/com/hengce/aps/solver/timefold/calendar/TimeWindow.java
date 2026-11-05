package com.hengce.aps.solver.timefold.calendar;

import java.time.LocalDateTime;

/**
 * 求解域内的可用时段（UTC 半开区间 {@code [start, end)}）。
 *
 * @param start 开始时间（含）
 * @param end 结束时间（不含）
 */
public record TimeWindow(LocalDateTime start, LocalDateTime end) {

    /**
     * 判断整段占用是否落在本窗口内。
     *
     * @param from 占用开始（含）
     * @param to 占用结束（不含）
     * @return {@code true} 表示完整落在窗口内
     */
    public boolean contains(LocalDateTime from, LocalDateTime to) {
        return !from.isBefore(start) && !to.isAfter(end);
    }
}
