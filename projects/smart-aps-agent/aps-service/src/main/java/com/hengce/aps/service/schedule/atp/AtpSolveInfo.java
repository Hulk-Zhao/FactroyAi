package com.hengce.aps.service.schedule.atp;

import java.math.BigDecimal;

/**
 * 估算/求解过程信息。
 *
 * @param engine              heuristic/timefold
 * @param timeoutSeconds      限时秒数；FAST 为 null
 * @param elapsedMs           本次处理耗时
 * @param timedOut            是否因限时终止
 * @param solverStatus        求解器状态；FAST 为 COMPLETED
 * @param scoreHard           硬分
 * @param scoreSoft           软分
 * @param assignedTaskCount   假设订单已分配工序数
 * @param unassignedTaskCount 假设订单未分配工序数
 * @param message             过程说明
 */
public record AtpSolveInfo(
        String engine,
        Integer timeoutSeconds,
        long elapsedMs,
        boolean timedOut,
        String solverStatus,
        BigDecimal scoreHard,
        BigDecimal scoreSoft,
        Integer assignedTaskCount,
        Integer unassignedTaskCount,
        String message) {
}
