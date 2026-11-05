package com.hengce.aps.solver.timefold.dependency;

import java.time.LocalDateTime;

/**
 * 计划解中的依赖边（问题事实）：后继与前序均以 {@code planningId} 引用任务实体。
 *
 * @param successorPlanningId 后继任务 planningId
 * @param predecessorPlanningId 前序任务 planningId
 * @param dependType 依赖类型：FS / SS / FF
 * @param lagMinutes 滞后分钟数
 */
public record TaskDependency(
        String successorPlanningId,
        String predecessorPlanningId,
        String dependType,
        int lagMinutes
) {

    /**
     * 按当前时间判断该依赖是否被违反。
     *
     * @param predecessorStart 前序开始时间
     * @param predecessorEnd 前序结束时间
     * @param successorStart 后继开始时间
     * @param successorEnd 后继结束时间
     * @return {@code true} 表示违反
     */
    public boolean violatedBy(
            LocalDateTime predecessorStart,
            LocalDateTime predecessorEnd,
            LocalDateTime successorStart,
            LocalDateTime successorEnd) {
        return OperationDependencyEvaluator.violated(dependType, lagMinutes,
                predecessorStart, predecessorEnd, successorStart, successorEnd);
    }
}
