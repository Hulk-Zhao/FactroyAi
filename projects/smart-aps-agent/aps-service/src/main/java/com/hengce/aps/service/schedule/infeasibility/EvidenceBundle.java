package com.hengce.aps.service.schedule.infeasibility;

import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import java.util.List;

/**
 * 确定性证据集合（C51 的中间产物）。
 *
 * <p>只装载可直接复算的证据：每条都能追溯到 {@code ScheduleProblem} 的字段或
 * {@code constraint_justification} 行；不包含任何模型生成或推断的内容。</p>
 *
 * @param blockingTasks 阻塞任务（未截断）
 * @param conflictPairs 冲突对（未截断）
 * @param resourceWindows 资源窗口缺口（未截断）
 * @param violatedRules 违反规则聚合（未截断）
 * @param assumptions 计算假设
 * @param infeasibilityProven 是否存在“结构性不可行”证明
 * @param proofNotes 证明说明（确定性文本，逐条可复算）
 * @param truncated 是否发生截断（任一类超过 8 条）
 */
public record EvidenceBundle(
        List<InfeasibilityReport.BlockingTask> blockingTasks,
        List<InfeasibilityReport.ConflictPair> conflictPairs,
        List<InfeasibilityReport.ResourceWindow> resourceWindows,
        List<InfeasibilityReport.ViolatedRule> violatedRules,
        List<String> assumptions,
        boolean infeasibilityProven,
        List<String> proofNotes,
        boolean truncated
) {

    /**
     * 归一化列表字段。
     */
    public EvidenceBundle {
        blockingTasks = List.copyOf(blockingTasks == null ? List.of() : blockingTasks);
        conflictPairs = List.copyOf(conflictPairs == null ? List.of() : conflictPairs);
        resourceWindows = List.copyOf(resourceWindows == null ? List.of() : resourceWindows);
        violatedRules = List.copyOf(violatedRules == null ? List.of() : violatedRules);
        assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        proofNotes = List.copyOf(proofNotes == null ? List.of() : proofNotes);
    }

    /**
     * 是否四类证据全部为空。
     *
     * @return 全空时返回 {@code true}
     */
    public boolean isEmpty() {
        return blockingTasks.isEmpty() && conflictPairs.isEmpty()
                && resourceWindows.isEmpty() && violatedRules.isEmpty();
    }
}
