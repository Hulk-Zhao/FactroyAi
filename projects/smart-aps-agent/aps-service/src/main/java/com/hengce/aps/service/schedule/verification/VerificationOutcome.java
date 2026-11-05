package com.hengce.aps.service.schedule.verification;

import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.util.List;

/**
 * 不落库验证求解的结果。
 *
 * <p><b>未落库标记是硬约束</b>：{@code persisted} 与 {@code mainPlanWritten} 构造时只能为
 * {@code false}，任何调用方拿到本对象都不会误以为计划已写入。本对象只由
 * {@link VerificationSolver} 产出，该服务不依赖 {@code ScheduleResultWriter}。</p>
 *
 * @param status 结论
 * @param engine 引擎编码
 * @param seed 复现 seed，可空
 * @param hardScore 硬分，未求解/失败时为空
 * @param softScore 软分，未求解/失败时为空
 * @param durationMs 求解耗时（毫秒）
 * @param terminationReason 结束原因，可空
 * @param totalTaskCount 目标工序总数
 * @param assignedTaskCount 目标已分配工序数
 * @param unassignedTaskCount 目标未分配工序数；{@code UNVERIFIED}/{@code OUT_OF_BUDGET} 时为 null
 * @param assignments 完整任务排布（仅 {@code FEASIBLE} 可用于对外展示）
 * @param justifications 约束归因（用于 {@code INFEASIBLE} 的证据采集）
 * @param persisted 恒为 {@code false}：本次求解未写任何计划表
 * @param mainPlanWritten 恒为 {@code false}
 * @param message 确定性说明
 */
public record VerificationOutcome(
        VerificationStatus status,
        String engine,
        Long seed,
        BigDecimal hardScore,
        BigDecimal softScore,
        long durationMs,
        String terminationReason,
        int totalTaskCount,
        int assignedTaskCount,
        Integer unassignedTaskCount,
        List<TaskAssignment> assignments,
        List<ConstraintJustification> justifications,
        boolean persisted,
        boolean mainPlanWritten,
        String message
) {

    /**
     * 归一化列表，并拒绝任何“已落库”的误标。
     */
    public VerificationOutcome {
        assignments = List.copyOf(assignments == null ? List.of() : assignments);
        justifications = List.copyOf(justifications == null ? List.of() : justifications);
        if (persisted) {
            throw new IllegalArgumentException("验证求解结果不得标记为已落库");
        }
        if (mainPlanWritten) {
            throw new IllegalArgumentException("验证求解结果不得标记为已写主计划");
        }
    }
}
