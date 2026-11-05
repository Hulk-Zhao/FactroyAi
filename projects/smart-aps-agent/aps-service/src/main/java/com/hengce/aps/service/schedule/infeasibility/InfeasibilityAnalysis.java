package com.hengce.aps.service.schedule.infeasibility;

import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import java.util.List;

/**
 * B1 归因结果：C51 证据 + 供 C52 的修复候选。
 *
 * @param report C51 报告
 * @param repairCandidates 修复候选（本轮不验证）
 */
public record InfeasibilityAnalysis(
        InfeasibilityReport report,
        List<RepairCandidate> repairCandidates
) {

    /**
     * 归一化候选列表。
     */
    public InfeasibilityAnalysis {
        repairCandidates = List.copyOf(repairCandidates == null ? List.of() : repairCandidates);
    }
}
