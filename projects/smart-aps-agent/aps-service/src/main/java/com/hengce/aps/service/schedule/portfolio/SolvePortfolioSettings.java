package com.hengce.aps.service.schedule.portfolio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Portfolio 运行时开关与参数。
 *
 * <p>默认关闭（灰度），开启后同一题面并行跑 N 个候选，只落库一个赢家。所有阈值都可通过
 * {@code aps.solve.portfolio.*} 配置覆盖，便于 A3 用固定预算做回归。</p>
 */
@Service
public final class SolvePortfolioSettings {

    /**
     * 契约 C54 冻结的候选数组上限。
     */
    public static final int CONTRACT_CANDIDATE_LIMIT = 8;

    /**
     * 编排允许的候选上限（超过 8 会被截断，因此配置侧先封顶）。
     */
    public static final int HARD_CANDIDATE_LIMIT = 5;

    private final boolean enabled;

    private final int candidates;

    private final int maxCandidates;

    private final int maxTasksForPortfolio;

    private final double candidateTimeoutRatio;

    private final boolean warmStartCandidate;

    private final long maxStepCount;

    /**
     * Spring 装配入口。
     *
     * @param enabled 是否启用 portfolio
     * @param candidates 期望候选数
     * @param maxCandidates 候选数上限（<=5）
     * @param maxTasksForPortfolio 超过该任务规模即降级为单候选
     * @param candidateTimeoutRatio 单候选超时相对整体预算的比例
     * @param warmStartCandidate 有冻结分配时是否追加 warm 候选
     * @param maxStepCount 固定步数预算覆盖（>0 时优先生效，便于可复现回归）
     */
    @Autowired
    public SolvePortfolioSettings(
            @Value("${aps.solve.portfolio.enabled:false}") boolean enabled,
            @Value("${aps.solve.portfolio.candidates:3}") int candidates,
            @Value("${aps.solve.portfolio.max-candidates:5}") int maxCandidates,
            @Value("${aps.solve.portfolio.max-tasks-for-portfolio:800}") int maxTasksForPortfolio,
            @Value("${aps.solve.portfolio.candidate-timeout-ratio:1.0}") double candidateTimeoutRatio,
            @Value("${aps.solve.portfolio.warm-start-candidate:true}") boolean warmStartCandidate,
            @Value("${aps.solve.portfolio.max-step-count:0}") long maxStepCount) {
        this(validate(enabled, candidates, maxCandidates, maxTasksForPortfolio,
                candidateTimeoutRatio, warmStartCandidate, maxStepCount));
    }

    private SolvePortfolioSettings(Values values) {
        this.enabled = values.enabled();
        this.candidates = values.candidates();
        this.maxCandidates = values.maxCandidates();
        this.maxTasksForPortfolio = values.maxTasksForPortfolio();
        this.candidateTimeoutRatio = values.candidateTimeoutRatio();
        this.warmStartCandidate = values.warmStartCandidate();
        this.maxStepCount = values.maxStepCount();
    }

    /**
     * 测试/手工装配入口，复用与 Spring 相同的归一化规则。
     *
     * @param enabled 是否启用
     * @param candidates 期望候选数
     * @param maxCandidates 候选数上限
     * @param maxTasksForPortfolio 任务规模阈值
     * @param candidateTimeoutRatio 单候选超时比例
     * @param warmStartCandidate 是否追加 warm 候选
     * @param maxStepCount 固定步数预算
     * @return 归一化后的设置
     */
    public static SolvePortfolioSettings forTest(
            boolean enabled,
            int candidates,
            int maxCandidates,
            int maxTasksForPortfolio,
            double candidateTimeoutRatio,
            boolean warmStartCandidate,
            long maxStepCount) {
        return new SolvePortfolioSettings(validate(enabled, candidates, maxCandidates,
                maxTasksForPortfolio, candidateTimeoutRatio, warmStartCandidate, maxStepCount));
    }

    /**
     * 是否启用。
     *
     * @return 启用为 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 期望候选数（未做运行时 gating）。
     *
     * @return 候选数
     */
    public int candidates() {
        return candidates;
    }

    /**
     * 配置上限。
     *
     * @return 候选上限
     */
    public int maxCandidates() {
        return maxCandidates;
    }

    /**
     * 超过该任务规模降级为单候选。
     *
     * @return 任务数阈值
     */
    public int maxTasksForPortfolio() {
        return maxTasksForPortfolio;
    }

    /**
     * 单候选超时相对整体预算的比例。
     *
     * @return 比例
     */
    public double candidateTimeoutRatio() {
        return candidateTimeoutRatio;
    }

    /**
     * 是否允许 warm 候选。
     *
     * @return 允许为 true
     */
    public boolean warmStartCandidate() {
        return warmStartCandidate;
    }

    /**
     * 固定步数预算覆盖值；0 表示沿用请求配置。
     *
     * @return 步数预算
     */
    public long maxStepCount() {
        return maxStepCount;
    }

    /**
     * 计算单候选超时秒数，至少 1 秒。
     *
     * @param budgetSeconds 整体预算秒数
     * @return 单候选超时秒数
     */
    public int candidateTimeoutSeconds(int budgetSeconds) {
        if (budgetSeconds <= 0) {
            return 1;
        }
        long scaled = (long) Math.ceil(budgetSeconds * candidateTimeoutRatio);
        return (int) Math.max(1, Math.min(scaled, budgetSeconds));
    }

    private static Values validate(
            boolean enabled,
            int candidates,
            int maxCandidates,
            int maxTasksForPortfolio,
            double candidateTimeoutRatio,
            boolean warmStartCandidate,
            long maxStepCount) {
        int effectiveMax = Math.max(1, Math.min(maxCandidates, HARD_CANDIDATE_LIMIT));
        int effectiveCandidates = Math.max(1, Math.min(candidates, effectiveMax));
        int effectiveMaxTasks = maxTasksForPortfolio > 0 ? maxTasksForPortfolio : 800;
        double effectiveRatio = candidateTimeoutRatio > 0 ? candidateTimeoutRatio : 1.0;
        long effectiveSteps = Math.max(0, maxStepCount);
        return new Values(enabled, effectiveCandidates, effectiveMax, effectiveMaxTasks,
                effectiveRatio, warmStartCandidate, effectiveSteps);
    }

    private record Values(
            boolean enabled,
            int candidates,
            int maxCandidates,
            int maxTasksForPortfolio,
            double candidateTimeoutRatio,
            boolean warmStartCandidate,
            long maxStepCount) {
    }
}
