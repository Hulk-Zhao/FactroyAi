package com.hengce.aps.solver.api;

import java.math.BigDecimal;

/**
 * 引擎无关评分投影；硬分必须为 0 才可发布。
 *
 * @param hardScore 硬分
 * @param softScore 软分
 * @param scoreText 引擎原始评分文本，便于排查
 */
public record ScoreFact(
        BigDecimal hardScore,
        BigDecimal softScore,
        String scoreText
) {
}
