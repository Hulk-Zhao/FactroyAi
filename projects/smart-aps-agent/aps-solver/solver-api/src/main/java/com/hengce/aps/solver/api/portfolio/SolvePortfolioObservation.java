package com.hengce.aps.solver.api.portfolio;

import com.hengce.aps.solver.api.ScorePoint;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * C54 并行求解观测，落到 {@code solve_task.metrics.portfolio}。
 *
 * <p>portfolio 关闭时该键不存在；候选最多 8 个、轨迹最多 200 点。
 * 不得伪造空对象或替代曲线。</p>
 *
 * @param enabled 是否启用 portfolio
 * @param candidateCount 实际候选数
 * @param winnerProfile 胜出 profile；无赢家时为 null
 * @param degraded 是否降级为单候选
 * @param degradedReason 降级原因；未降级为 null
 * @param slotWaitMs 槽位等待；未等待为 0，无法测量为 null
 * @param candidates 候选明细，最多 8 个
 * @param timeToBestMs 首个最佳解耗时；无事件为 null
 * @param scoreTimeline 评分轨迹，最多 200 点
 * @param truncated 是否截断
 * @param generatedAt 观测生成时间
 */
public record SolvePortfolioObservation(
        boolean enabled,
        int candidateCount,
        String winnerProfile,
        boolean degraded,
        SolvePortfolioDegradedReason degradedReason,
        Long slotWaitMs,
        List<SolvePortfolioCandidate> candidates,
        Long timeToBestMs,
        List<ScorePoint> scoreTimeline,
        boolean truncated,
        OffsetDateTime generatedAt
) {

    public SolvePortfolioObservation {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        scoreTimeline = List.copyOf(scoreTimeline == null ? List.of() : scoreTimeline);
    }
}
