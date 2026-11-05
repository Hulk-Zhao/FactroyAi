package com.hengce.aps.service.schedule.portfolio;

/**
 * Portfolio 候选数准入（内存 + CPU + 任务规模 gating）。
 *
 * <p>内存估算来自 roadmap R1 的实测两点拟合（300 任务 ≈ 833MB、3000 任务 ≈ 1458MB）：</p>
 * <code>peakMb(n) = 763.6 + 0.2315 × n</code>
 * <p>单候选按 1.2 倍峰值预留、且不低于 300MB；可用堆扣除 800MB 常驻开销后，
 * 可运行候选数 = floor(可用堆 / 单候选预留)。</p>
 *
 * <p><b>为什么任务规模阈值是 800：</b>800 任务时单候选预留约 1.14GB，3 候选约 3.4GB；
 * 加上 800MB 常驻后仍可在 8GB 堆内运行，但在 4GB 堆内已不可能。超过 800 任务后，
 * CH 阶段与局部搜索的墙钟时间也线性增长，并行候选反而拖慢整体交付；R1 在 3000 任务 ×
 * 3 候选时观测到单会话峰值 1.46GB，因此硬性降为单候选。</p>
 */
public final class PortfolioAdmission {

    /**
     * 固定开销（MB），由两点拟合截距得到。
     */
    static final double BASE_PEAK_MB = 763.6;

    /**
     * 每任务增量（MB），由两点拟合斜率得到。
     */
    static final double PER_TASK_PEAK_MB = 0.2315;

    /**
     * 候选预留相对峰值的安全系数。
     */
    static final double CANDIDATE_SAFETY_FACTOR = 1.2;

    /**
     * 单候选最低预留（MB），覆盖空题面/极小规模下的固定结构开销。
     */
    static final double MIN_CANDIDATE_MB = 300.0;

    /**
     * 为 Spring 容器、数据库连接池、GC 与其它任务保留的堆（MB）。
     */
    static final double RESERVED_HEAP_MB = 800.0;

    /**
     * 为 HTTP/DB/GC 预留的核数。
     */
    static final int RESERVED_PROCESSORS = 2;

    private PortfolioAdmission() {
    }

    /**
     * 准入结果。
     *
     * @param effectiveCandidates 实际开跑候选数
     * @param degraded 是否被迫降级
     * @param reason 降级原因（未降级为 null）
     * @param explanation 人类可读的判定说明（落日志用）
     */
    public record Admission(
            int effectiveCandidates,
            boolean degraded,
            com.hengce.aps.solver.api.portfolio.SolvePortfolioDegradedReason reason,
            String explanation) {
    }

    /**
     * 计算候选数。
     *
     * @param settings portfolio 设置
     * @param taskCount 题面任务（工序）数
     * @param processors 可用处理器数
     * @param maxHeapBytes 最大堆字节数；<=0 表示无法测量
     * @return 准入结果
     */
    public static Admission admit(
            SolvePortfolioSettings settings,
            int taskCount,
            int processors,
            long maxHeapBytes) {
        int requested = settings.candidates();
        if (requested <= 1) {
            return new Admission(1, false, null,
                    "portfolio 配置本身为单候选（candidates=" + requested + "）");
        }
        if (taskCount > settings.maxTasksForPortfolio()) {
            return new Admission(1, true,
                    com.hengce.aps.solver.api.portfolio.SolvePortfolioDegradedReason.TASK_SCALE,
                    "任务数 " + taskCount + " 超过 portfolio 阈值 "
                            + settings.maxTasksForPortfolio() + "，降为单候选");
        }

        int cpuPermits = Math.max(1, processors - RESERVED_PROCESSORS);
        int memoryPermits;
        double candidateMb;
        if (maxHeapBytes <= 0) {
            memoryPermits = requested;
            candidateMb = perCandidateMb(taskCount);
        } else {
            candidateMb = perCandidateMb(taskCount);
            double availableMb = Math.max(0.0, maxHeapBytes / (1024.0 * 1024.0) - RESERVED_HEAP_MB);
            memoryPermits = Math.max(1, (int) Math.floor(availableMb / candidateMb));
        }

        int effective = Math.max(1, Math.min(requested, Math.min(cpuPermits, memoryPermits)));
        if (effective >= requested) {
            return new Admission(requested, false, null,
                    "准入通过：requested=" + requested + ", cpu=" + cpuPermits
                            + ", mem=" + memoryPermits + ", perCandidateMb="
                            + String.format(java.util.Locale.ROOT, "%.1f", candidateMb));
        }

        com.hengce.aps.solver.api.portfolio.SolvePortfolioDegradedReason reason =
                memoryPermits < requested
                        ? com.hengce.aps.solver.api.portfolio.SolvePortfolioDegradedReason.MEMORY_BUDGET
                        : com.hengce.aps.solver.api.portfolio.SolvePortfolioDegradedReason.CPU_BUDGET;
        return new Admission(effective, true, reason,
                "候选数由 " + requested + " 降至 " + effective + "：cpuPermits=" + cpuPermits
                        + ", memoryPermits=" + memoryPermits
                        + ", perCandidateMb="
                        + String.format(java.util.Locale.ROOT, "%.1f", candidateMb));
    }

    /**
     * 单候选堆预留估算。
     *
     * @param taskCount 任务数
     * @return MB
     */
    public static double perCandidateMb(int taskCount) {
        double peakMb = BASE_PEAK_MB + PER_TASK_PEAK_MB * Math.max(0, taskCount);
        return Math.max(MIN_CANDIDATE_MB, CANDIDATE_SAFETY_FACTOR * peakMb);
    }
}
