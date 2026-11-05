package com.hengce.aps.solver.api;

import java.util.List;
import java.util.Map;

/**
 * 求解度量，对应 {@code solve_task.metrics} JSONB。
 *
 * <p>不提供 {@code stepCount}/{@code unimprovedStepCount}：Timefold 2.8.0 社区版没有步数口径，
 * 恒 0 的键会被读成“0 步”，属于失真输出，因此既不落库也不对外输出。</p>
 *
 * @param durationMs 求解耗时（毫秒）
 * @param terminationReason 结束原因：TIME_LIMIT / STEP_LIMIT / UNIMPROVED_LIMIT / TERMINATED
 * @param scoreHistory 评分轨迹
 * @param assignedTaskCount 已分配任务数
 * @param extra 引擎扩展度量
 */
public record SolveMetrics(
        long durationMs,
        String terminationReason,
        List<ScorePoint> scoreHistory,
        int assignedTaskCount,
        Map<String, Object> extra
) {

    /**
     * 归一化评分轨迹。
     */
    public SolveMetrics {
        scoreHistory = List.copyOf(scoreHistory == null ? List.of() : scoreHistory);
    }
}
