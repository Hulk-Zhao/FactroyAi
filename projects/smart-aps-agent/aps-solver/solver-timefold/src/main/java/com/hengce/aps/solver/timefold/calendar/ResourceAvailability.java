package com.hengce.aps.solver.timefold.calendar;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 单个资源的可用时段要求；空窗口列表表示 7×24，不产生约束。
 *
 * @param resourceId 资源主键
 * @param resourceCode 资源编码
 * @param windows 可用时段
 */
public record ResourceAvailability(
        UUID resourceId,
        String resourceCode,
        List<TimeWindow> windows
) {

    /**
     * 判断整段占用是否被该资源允许。
     *
     * @param from 占用开始（含）
     * @param to 占用结束（不含）
     * @return {@code true} 表示允许
     */
    public boolean allows(LocalDateTime from, LocalDateTime to) {
        if (windows.isEmpty()) {
            return true;
        }
        return windows.stream().anyMatch(window -> window.contains(from, to));
    }
}
