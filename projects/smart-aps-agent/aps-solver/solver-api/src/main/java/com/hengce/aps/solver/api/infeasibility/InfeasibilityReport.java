package com.hengce.aps.solver.api.infeasibility;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * C51 求解不可行证据。
 *
 * <p>数组每类最多 8 条，截断时 {@code truncated=true}；完整证据留在
 * {@code constraint_justification.implicated_facts}。失败/超时任务同样可查。</p>
 *
 * @param solveTaskId 求解任务主键
 * @param verdict 结论；禁止不可行证明口径
 * @param blockingTasks 阻塞/未分配任务
 * @param conflictPairs 资源/顺序冲突对
 * @param resourceWindows 资源窗口与需求缺口
 * @param violatedRules 违反规则聚合
 * @param assumptions 计算假设
 * @param generatedAt 证据生成时间；NO_EVIDENCE 时为 null
 * @param solverRunId 求解运行标识；引擎不暴露时为 null
 * @param candidateSeed 复现 seed；不暴露时为 null
 * @param truncated 是否发生截断
 * @param narrative 面向人的说明；LLM 只改写，不覆盖事实
 * @param llmStatus LLM 状态
 */
public record InfeasibilityReport(
        long solveTaskId,
        InfeasibilityVerdict verdict,
        List<BlockingTask> blockingTasks,
        List<ConflictPair> conflictPairs,
        List<ResourceWindow> resourceWindows,
        List<ViolatedRule> violatedRules,
        List<String> assumptions,
        OffsetDateTime generatedAt,
        String solverRunId,
        Long candidateSeed,
        boolean truncated,
        String narrative,
        LlmStatus llmStatus
) {

    public InfeasibilityReport {
        verdict = verdict == null ? InfeasibilityVerdict.NO_EVIDENCE : verdict;
        blockingTasks = List.copyOf(blockingTasks == null ? List.of() : blockingTasks);
        conflictPairs = List.copyOf(conflictPairs == null ? List.of() : conflictPairs);
        resourceWindows = List.copyOf(resourceWindows == null ? List.of() : resourceWindows);
        violatedRules = List.copyOf(violatedRules == null ? List.of() : violatedRules);
        assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        llmStatus = llmStatus == null ? LlmStatus.DISABLED : llmStatus;
    }

    /**
     * 阻塞任务。
     *
     * @param taskId 已落库任务主键；无路由等场景为 null
     * @param orderNo 订单编码
     * @param operation 工序编码/名称；无路由时为 null
     * @param operationIndex 工序序号；无路由时为 null
     * @param reasonCode 原因码
     * @param reason 确定性说明
     */
    public record BlockingTask(
            Long taskId,
            String orderNo,
            String operation,
            Integer operationIndex,
            InfeasibilityReasonCode reasonCode,
            String reason
    ) {
    }

    /**
     * 冲突对。
     */
    public record ConflictPair(
            long taskA,
            long taskB,
            UUID resourceId,
            OffsetDateTime overlapStart,
            OffsetDateTime overlapEnd
    ) {
    }

    /**
     * 资源窗口缺口。
     */
    public record ResourceWindow(
            UUID resourceId,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            BigDecimal requiredHours,
            BigDecimal gapHours
    ) {
    }

    /**
     * 违反规则聚合。
     */
    public record ViolatedRule(
            String ruleCode,
            String constraintName,
            BigDecimal deduction,
            int count
    ) {
    }

    /**
     * LLM 叙事状态。
     */
    public enum LlmStatus {
        DISABLED,
        OK,
        FALLBACK
    }
}
