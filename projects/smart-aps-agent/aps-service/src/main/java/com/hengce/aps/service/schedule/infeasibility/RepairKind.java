package com.hengce.aps.service.schedule.infeasibility;

/**
 * 修复候选类型；取值与 C52 {@code CounterfactualRequest.kind} 冻结枚举一一对应。
 *
 * <p>本轮只产出候选结构，不做反事实验证（验证属 C52 车道）。</p>
 */
public enum RepairKind {
    EXTEND_CALENDAR,
    ADD_RESOURCE,
    UNFREEZE_TASKS,
    RELAX_DUE_DATE,
    SPLIT_BATCH,
    SUBSTITUTE_MATERIAL
}
