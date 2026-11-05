package com.hengce.aps.service.schedule;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 求解结束事件（A0 后置钩子接缝）。
 *
 * <p>覆盖所有终态：{@code SOLVED} / {@code FAILED} / {@code TIMEOUT} / {@code CANCELLED}。
 * 归因（B1）、portfolio 指标（A1）等后续能力只读本事件，不改写求解主流程。</p>
 *
 * @param taskId 求解任务主键
 * @param tenantId 租户标识
 * @param status 终态名称
 * @param scoreHard 硬分，可空（失败/超时无结果）
 * @param scoreSoft 软分，可空
 * @param metrics 已合并路由与降级标记的度量
 * @param message 终态说明
 * @param problem 题面引用，可空（A1 追加的可选载荷）
 * @param solution 结果引用，可空（A1 追加的可选载荷）
 */
public record SolveFinishEvent(
        long taskId,
        UUID tenantId,
        String status,
        Long scoreHard,
        Long scoreSoft,
        Map<String, Object> metrics,
        String message,
        ScheduleProblem problem,
        ScheduleSolution solution
) {

    /**
     * 兼容既有 7 参构造（A0 调用方）：可选载荷为 null。
     *
     * @param taskId 求解任务主键
     * @param tenantId 租户标识
     * @param status 终态名称
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metrics 度量
     * @param message 终态说明
     */
    public SolveFinishEvent(
            long taskId,
            UUID tenantId,
            String status,
            Long scoreHard,
            Long scoreSoft,
            Map<String, Object> metrics,
            String message) {
        this(taskId, tenantId, status, scoreHard, scoreSoft, metrics, message, null, null);
    }

    /**
     * 复制度量映射，避免后置处理器修改主流程正在落库的同一份数据。
     *
     * <p>不能用 {@link Map#copyOf}：metrics 允许显式 null 值（例如失败的 bestScore）。</p>
     */
    public SolveFinishEvent {
        metrics = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(metrics == null ? Map.of() : metrics));
    }

    /**
     * 是否为成功终态。
     *
     * @return {@code SOLVED} 时为 true
     */
    public boolean solved() {
        return "SOLVED".equals(Objects.requireNonNullElse(status, ""));
    }
}
