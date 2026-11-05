package com.hengce.aps.service.schedule.portfolio;

import com.hengce.aps.solver.api.ScheduleSolution;
import java.util.Map;

/**
 * Portfolio 一次运行的输出。
 *
 * <p>{@code solution} 为赢家；没有赢家时退回“诊断用最佳候选”（可能是部分解），
 * 由 {@code ScheduleService} 走既有的非 SOLVED 分支如实标记失败原因。</p>
 *
 * @param solution 赢家或诊断用最佳候选；全候选未产出任何解时为 null
 * @param cancelled 是否因外部取消/终止信号放弃
 * @param timedOut 是否因整体预算耗尽而停止
 * @param failureReason 无解可回退时的原因（TIMEOUT / SOLVER_BUSY / ENGINE_ERROR）；否则 null
 * @param message 人类可读说明
 * @param observation 可直接放入 {@code metrics.portfolio} 的 Map
 * @param winnerProfile 赢家画像名；无赢家为 null
 */
public record PortfolioOutcome(
        ScheduleSolution solution,
        boolean cancelled,
        boolean timedOut,
        String failureReason,
        String message,
        Map<String, Object> observation,
        String winnerProfile
) {
}
