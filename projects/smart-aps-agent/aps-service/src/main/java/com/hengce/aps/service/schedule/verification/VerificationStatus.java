package com.hengce.aps.service.schedule.verification;

/**
 * 一次只读验证求解的结论。
 *
 * <p>语义边界：</p>
 * <ul>
 *   <li>{@code FEASIBLE}：hard=0 且目标工序全部已分配；</li>
 *   <li>{@code INFEASIBLE}：求解器给出 hard&lt;0，或未分配工序且不是预算耗尽；</li>
 *   <li>{@code OUT_OF_BUDGET}：预算（时限/槽位等待）先耗尽，<b>不是</b>不可行结论；</li>
 *   <li>{@code UNVERIFIED}：求解器不可用或参数/题面不足以验证。</li>
 * </ul>
 */
public enum VerificationStatus {
    FEASIBLE,
    INFEASIBLE,
    OUT_OF_BUDGET,
    UNVERIFIED
}
