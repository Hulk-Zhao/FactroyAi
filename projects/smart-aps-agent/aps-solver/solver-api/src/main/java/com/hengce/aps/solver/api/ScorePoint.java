package com.hengce.aps.solver.api;

import java.math.BigDecimal;

/**
 * 评分轨迹采样点。
 *
 * @param elapsedMs 相对求解开始的毫秒数
 * @param step 求解步号
 * @param hardScore 硬分
 * @param softScore 软分
 */
public record ScorePoint(
        long elapsedMs,
        long step,
        BigDecimal hardScore,
        BigDecimal softScore
) {
}
