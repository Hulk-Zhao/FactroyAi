package com.hengce.aps.solver.api.counterfactual;

/**
 * C52 反事实验证状态。
 *
 * <p>只有 {@code VERIFIED_FEASIBLE} 允许 {@code feasible=true} 并返回新日期/资源；
 * {@code UNVERIFIED} / {@code OUT_OF_BUDGET} 不得展示为可行。</p>
 */
public enum CounterfactualStatus {
    VERIFIED_FEASIBLE,
    STILL_INFEASIBLE,
    UNVERIFIED,
    OUT_OF_BUDGET
}
