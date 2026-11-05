package com.hengce.aps.solver.api.counterfactual;

/**
 * C52 反事实修复类型。
 */
public enum CounterfactualKind {
    EXTEND_CALENDAR,
    ADD_RESOURCE,
    UNFREEZE_TASKS,
    RELAX_DUE_DATE,
    SPLIT_BATCH,
    SUBSTITUTE_MATERIAL
}
