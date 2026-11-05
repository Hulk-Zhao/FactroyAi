package com.hengce.aps.solver.api.infeasibility;

/**
 * C51 求解不可行证据结论。
 *
 * <p>{@code NO_FEASIBLE_SOLUTION_FOUND} 只表示给定题面与时间预算内未找到可行解，
 * 不是数学意义上的不可行证明；禁止出现 {@code INFEASIBLE_PROVEN}。</p>
 */
public enum InfeasibilityVerdict {
    NO_FEASIBLE_SOLUTION_FOUND,
    NO_EVIDENCE,
    PARTIAL_EVIDENCE
}
