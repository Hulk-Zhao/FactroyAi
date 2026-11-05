package com.hengce.aps.solver.api.portfolio;

import java.math.BigDecimal;

/**
 * C54 单个并行求解候选。
 *
 * @param profile 候选 profile 名
 * @param seed 候选 seed
 * @param status 候选状态
 * @param durationMs 耗时；REJECTED 未启动求解时为 null
 * @param hardScore 硬分；未产出时为 null
 * @param softScore 软分；未产出时为 null
 * @param terminationReason 引擎终止原因；未运行为 null
 * @param winner 是否胜出；全部失败时全为 false
 * @param failureReason 失败原因；成功时为 null
 */
public record SolvePortfolioCandidate(
        String profile,
        long seed,
        SolvePortfolioCandidateStatus status,
        Long durationMs,
        BigDecimal hardScore,
        BigDecimal softScore,
        String terminationReason,
        boolean winner,
        SolvePortfolioFailureReason failureReason
) {
}
