package com.hengce.aps.solver.api;

import java.util.List;

/**
 * 引擎中立的求解输出。
 *
 * <p>{@code status} 取值：SOLVED / INFEASIBLE / TIMEOUT / FAILED；
 * 只有 {@code SOLVED} 且硬分为 0 的结果才允许发布。列表字段为空时归一化为空列表。</p>
 *
 * @param status 求解状态
 * @param score 引擎无关评分，失败/未求解时为空
 * @param assignments 排产分配结果
 * @param justifications 约束归因
 * @param metrics 求解度量
 * @param message 附加说明
 */
public record ScheduleSolution(
        String status,
        ScoreFact score,
        List<TaskAssignment> assignments,
        List<ConstraintJustification> justifications,
        SolveMetrics metrics,
        String message
) {

    /**
     * 归一化列表字段。
     */
    public ScheduleSolution {
        assignments = List.copyOf(assignments == null ? List.of() : assignments);
        justifications = List.copyOf(justifications == null ? List.of() : justifications);
    }
}
