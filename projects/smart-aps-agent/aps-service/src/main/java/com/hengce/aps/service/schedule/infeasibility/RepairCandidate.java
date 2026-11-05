package com.hengce.aps.service.schedule.infeasibility;

import com.hengce.aps.solver.api.infeasibility.InfeasibilityReasonCode;
import java.util.List;
import java.util.Map;

/**
 * 给 C52 的修复候选（未经反事实验证）。
 *
 * @param kind C52 冻结的 kind
 * @param reasonCode 触发该候选的 C51 原因码
 * @param params C52 约定的 params 键；值只来自本次题面
 * @param taskIds 受影响任务主键（可为空）
 * @param rationale 确定性说明
 * @param rank 排序权重，越小越优先
 */
public record RepairCandidate(
        RepairKind kind,
        InfeasibilityReasonCode reasonCode,
        Map<String, Object> params,
        List<Long> taskIds,
        String rationale,
        int rank
) {

    /**
     * 归一化字段。
     */
    public RepairCandidate {
        params = Map.copyOf(params == null ? Map.of() : params);
        taskIds = List.copyOf(taskIds == null ? List.of() : taskIds);
    }
}
