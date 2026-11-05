package com.hengce.aps.solver.api;

import java.util.List;

/**
 * 求解结果。
 *
 * @param taskNo 求解任务号
 * @param status 求解状态
 * @param tasks 排产结果
 * @param message 附加说明
 */
public record SolveResult(
        String taskNo,
        SolveStatus status,
        List<ScheduledTask> tasks,
        String message) {
}
