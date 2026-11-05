package com.hengce.aps.solver.api.counterfactual;

import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * C52 反事实验证结果。
 *
 * <p>只读：不写 {@code plan_version}/{@code task}/{@code orders}/{@code solve_task}，
 * {@code mainPlanWritten} 恒为 false。仅 {@code VERIFIED_FEASIBLE} 的 {@code changes}
 * 可以携带新日期/资源；{@code STILL_INFEASIBLE} 不得返回建议日期。</p>
 *
 * @param solveTaskId 原求解任务主键
 * @param kind 回显修复类型
 * @param status 验证状态
 * @param feasible 仅 VERIFIED_FEASIBLE 为 true
 * @param affectedTaskCount 修改后题面受影响任务数
 * @param unassignedTaskCount 未分配任务数；UNVERIFIED/OUT_OF_BUDGET 为 null
 * @param changes 变更建议；最多 20 条
 * @param truncated changes 是否截断
 * @param solver 求解摘要；未启动求解时为 null
 * @param evidence 仅 STILL_INFEASIBLE 非空
 * @param assumptions 验证假设
 * @param narrative 面向人的说明
 * @param message 确定性结论文案
 * @param generatedAt 响应生成时间
 * @param mainPlanWritten 恒为 false
 */
public record CounterfactualResult(
        long solveTaskId,
        CounterfactualKind kind,
        CounterfactualStatus status,
        boolean feasible,
        int affectedTaskCount,
        Integer unassignedTaskCount,
        List<Change> changes,
        boolean truncated,
        Solver solver,
        InfeasibilityReport evidence,
        List<String> assumptions,
        String narrative,
        String message,
        OffsetDateTime generatedAt,
        boolean mainPlanWritten
) {

    public CounterfactualResult {
        changes = List.copyOf(changes == null ? List.of() : changes);
        assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
    }

    /**
     * 单条变更建议；日期/资源只能来自求解结果。
     */
    public record Change(
            long taskId,
            String orderNo,
            int operationIndex,
            String resourceCode,
            OffsetDateTime startTime,
            OffsetDateTime endTime
    ) {
    }

    /**
     * 求解过程摘要。
     */
    public record Solver(
            String engine,
            Long seed,
            BigDecimal hardScore,
            BigDecimal softScore,
            Long durationMs,
            String terminationReason
    ) {
    }
}
