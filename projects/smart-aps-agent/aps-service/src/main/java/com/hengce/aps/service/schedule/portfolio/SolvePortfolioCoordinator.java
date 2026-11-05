package com.hengce.aps.service.schedule.portfolio;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.schedule.ScheduleService;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScorePoint;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.portfolio.SolvePortfolioCandidateStatus;
import com.hengce.aps.solver.api.portfolio.SolvePortfolioDegradedReason;
import com.hengce.aps.solver.api.portfolio.SolvePortfolioFailureReason;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Portfolio 运行时：同一 {@link ScheduleProblem} 并行跑 N 个候选，选优一个赢家。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>候选数先经 {@link PortfolioAdmission} 做任务规模/内存/CPU gating；</li>
 *   <li>每个候选独立 plan（各自调用适配器）、独立 seed 与 moveSelectors；</li>
 *   <li>候选级异常被隔离，单候选失败不影响其它候选；</li>
 *   <li>候选级超时、整体预算超时、外部取消三条路径都会取消所有 Future 并关闭执行器；</li>
 *   <li>只返回一个赢家，结果落库仍由 {@code ScheduleService} 的既有写入链路完成。</li>
 * </ul>
 */
@Service
public final class SolvePortfolioCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolvePortfolioCoordinator.class);

    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static final long CANCEL_AWAIT_MILLIS = 500;

    private static final long NORMAL_AWAIT_MILLIS = 1_000;

    private final SolvePortfolioSettings settings;

    private final IntSupplier processors;

    private final LongSupplier maxHeapBytes;

    /**
     * Spring 装配入口。
     *
     * @param settings portfolio 设置
     */
    @Autowired
    public SolvePortfolioCoordinator(SolvePortfolioSettings settings) {
        this(settings, () -> Runtime.getRuntime().availableProcessors(),
                () -> Runtime.getRuntime().maxMemory());
    }

    /**
     * 可注入 CPU/堆的测试入口。
     *
     * @param settings portfolio 设置
     * @param processors 处理器数提供器
     * @param maxHeapBytes 最大堆提供器
     */
    public SolvePortfolioCoordinator(
            SolvePortfolioSettings settings,
            IntSupplier processors,
            LongSupplier maxHeapBytes) {
        this.settings = settings;
        this.processors = processors;
        this.maxHeapBytes = maxHeapBytes;
    }

    /**
     * 是否启用。
     *
     * @return settings 中 enabled 为 true
     */
    public boolean isEnabled() {
        return settings.isEnabled();
    }

    /**
     * 运行一次 portfolio。
     *
     * @param problem 题面
     * @param config 请求配置
     * @param adapter 求解适配器
     * @param context 运行上下文（任务规模、取消信号、进度回调）
     * @return 运行结果
     */
    public PortfolioOutcome run(
            ScheduleProblem problem,
            SolveConfig config,
            SolverAdapter adapter,
            PortfolioContext context) {
        long startNanos = System.nanoTime();
        PortfolioAdmission.Admission admission = PortfolioAdmission.admit(
                settings, context.taskCount(), processors.getAsInt(), maxHeapBytes.getAsLong());
        List<CandidateSpec> specs = candidateSpecs(problem, config.randomSeed());
        int started = admission.effectiveCandidates();
        long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(Math.max(1, config.timeoutSeconds()));
        Map<Integer, CandidateResult> results = new LinkedHashMap<>();
        ExecutorService executor = newExecutor(started, context.solveTaskId());
        boolean cancelled = false;
        boolean budgetExhausted = false;
        boolean interrupted = false;
        List<Future<CandidateResult>> futures = new ArrayList<>(started);
        try {
            CompletionService<CandidateResult> completion = new ExecutorCompletionService<>(executor);
            for (int index = 0; index < started; index++) {
                CandidateSpec spec = specs.get(index);
                SolveConfig candidateConfig = candidateConfig(config, spec);
                long candidateDeadlineNanos = Math.min(deadlineNanos,
                        startNanos + TimeUnit.SECONDS.toNanos(candidateConfig.timeoutSeconds()));
                Callable<CandidateResult> task = () -> runCandidate(
                        problem, candidateConfig, adapter, spec, candidateDeadlineNanos);
                futures.add(completion.submit(task));
            }
            int completed = 0;
            while (completed < started) {
                if (context.stopSignal() != null && context.stopSignal().shouldStop()) {
                    cancelled = true;
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    interrupted = true;
                    cancelled = true;
                    break;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    budgetExhausted = true;
                    break;
                }
                Future<CandidateResult> done = completion.poll(
                        Math.min(remainingNanos, POLL_INTERVAL_NANOS), TimeUnit.NANOSECONDS);
                if (done == null) {
                    continue;
                }
                CandidateResult result = done.get();
                results.put(result.spec().order(), result);
                completed++;
                publishProgress(context, completed, started, result);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            cancelled = true;
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            LOGGER.warn("portfolio 候选完成异常（已隔离）", exception);
        } finally {
            for (Future<CandidateResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            shutdown(executor, cancelled || budgetExhausted);
            if (interrupted) {
                Thread.interrupted();
            }
        }

        for (int index = 0; index < started; index++) {
            CandidateSpec spec = specs.get(index);
            results.computeIfAbsent(spec.order(), order -> CandidateResult.aborted(
                    spec, elapsedMillis(startNanos)));
        }
        for (int index = started; index < specs.size(); index++) {
            CandidateSpec spec = specs.get(index);
            results.putIfAbsent(spec.order(), CandidateResult.rejected(spec));
        }

        List<CandidateResult> ordered = new ArrayList<>(specs.size());
        for (CandidateSpec spec : specs) {
            ordered.add(results.get(spec.order()));
        }
        CandidateResult winner = selectWinner(ordered, context.taskCount());
        CandidateResult bestEffort = winner == null ? selectBestEffort(ordered) : winner;
        List<ScorePoint> timeline = bestEffort == null || bestEffort.solution() == null
                ? List.of() : bestEffort.solution().metrics().scoreHistory();
        ScoreTimelines.Timeline limitedTimeline = ScoreTimelines.limit(timeline);

        boolean runtimeOom = ordered.stream().anyMatch(CandidateResult::outOfMemory);
        List<CandidateResult> startedResults = ordered.stream()
                .filter(result -> result.spec().order() < started)
                .toList();
        boolean anyBusy = startedResults.stream()
                .anyMatch(result -> result.failureReason() == SolvePortfolioFailureReason.SOLVER_BUSY);
        boolean anyTimeout = startedResults.stream()
                .anyMatch(result -> result.failureReason() == SolvePortfolioFailureReason.TIMEOUT);
        boolean anyEngineError = startedResults.stream()
                .anyMatch(result -> result.failureReason() == SolvePortfolioFailureReason.ENGINE_ERROR);
        SolvePortfolioDegradedReason degradedReason = admission.reason();
        boolean degraded = admission.degraded();
        // 取消不是“降级”：候选是被主动中止的，不能借此把 degradedReason 写成 TIMEOUT/UNKNOWN。
        if (!degraded && !cancelled) {
            if (runtimeOom) {
                degraded = true;
                degradedReason = SolvePortfolioDegradedReason.OOM;
            } else if (anyBusy) {
                degraded = true;
                degradedReason = SolvePortfolioDegradedReason.SOLVER_BUSY;
            } else if (anyTimeout) {
                // C54 的 9 值枚举没有 TIMEOUT，候选级/整体预算超时只能落到 UNKNOWN。
                degraded = true;
                degradedReason = SolvePortfolioDegradedReason.UNKNOWN;
            } else if (anyEngineError) {
                // C54 的 9 值枚举没有 ENGINE_ERROR，具体失败原因保留在 candidates[].failureReason。
                degraded = true;
                degradedReason = SolvePortfolioDegradedReason.UNKNOWN;
            }
        }

        CandidateResult observedWinner = cancelled ? null : winner;
        List<Map<String, Object>> candidateMaps = new ArrayList<>(ordered.size());
        boolean candidatesTruncated = false;
        for (CandidateResult result : ordered) {
            if (candidateMaps.size() >= SolvePortfolioSettings.CONTRACT_CANDIDATE_LIMIT) {
                candidatesTruncated = true;
                break;
            }
            candidateMaps.add(candidateMap(result, result == observedWinner));
        }
        Map<String, Object> observation = observation(
                specs.size(), observedWinner, degraded, degradedReason,
                ordered, candidateMaps, candidatesTruncated || limitedTimeline.truncated(), limitedTimeline);

        if (cancelled) {
            return new PortfolioOutcome(null, true, false, null,
                    "求解已取消/终止，候选结果丢弃", observation, null);
        }
        if (budgetExhausted && winner == null) {
            return new PortfolioOutcome(null, false, true, "TIMEOUT",
                    "portfolio 整体预算耗尽，未完成候选被终止", observation, null);
        }
        if (winner != null) {
            return new PortfolioOutcome(winner.solution(), false, false, null,
                    "portfolio 赢家 " + winner.spec().profile().label()
                            + "（hard=" + winner.hardScore() + ", soft=" + winner.softScore() + "）",
                    observation, winner.spec().profile().label());
        }
        if (bestEffort != null && bestEffort.solution() != null) {
            return new PortfolioOutcome(bestEffort.solution(), false, false, null,
                    "portfolio 无满足发布条件的候选，退回最佳候选 "
                            + bestEffort.spec().profile().label() + " 供失败诊断",
                    observation, null);
        }
        String failureReason = failureReason(ordered, started);
        return new PortfolioOutcome(null, false, false, failureReason,
                "portfolio 全部候选未产出结果（started=" + started + ", reason=" + failureReason + "）",
                observation, null);
    }

    /**
     * 候选集合：默认 baseline/subchain/ruin；题面存在冻结或预热分配时再追加 warm 变体。
     */
    private List<CandidateSpec> candidateSpecs(ScheduleProblem problem, long baseSeed) {
        boolean warmAvailable = settings.warmStartCandidate()
                && (!problem.initialAssignments().isEmpty() || !problem.frozenAssignments().isEmpty());
        List<CandidateProfile> profiles = new ArrayList<>();
        for (CandidateProfile profile : CandidateProfile.values()) {
            if (profile.requiresWarmStart() && !warmAvailable) {
                continue;
            }
            profiles.add(profile);
        }
        int requested = settings.candidates();
        List<CandidateSpec> specs = new ArrayList<>(requested);
        for (int index = 0; index < requested && index < profiles.size(); index++) {
            CandidateProfile profile = profiles.get(index);
            specs.add(new CandidateSpec(profile, baseSeed + profile.seedOffset(), index));
        }
        return specs;
    }

    /**
     * 由请求配置派生候选配置：独立 seed、独立 moveSelectors、单候选超时与步数预算。
     */
    private SolveConfig candidateConfig(SolveConfig config, CandidateSpec spec) {
        Map<String, Object> parameters = new LinkedHashMap<>(config.parameters());
        if (!spec.profile().moveSelectors().isBlank()) {
            parameters.put("moveSelectors", spec.profile().moveSelectors());
        }
        // 画像名随参数透传，便于候选日志与 A3 基线归因。
        parameters.put("portfolioProfile", spec.profile().label());
        long steps = settings.maxStepCount() > 0 ? settings.maxStepCount() : config.maximumStepCount();
        int candidateTimeout = settings.candidateTimeoutSeconds(config.timeoutSeconds());
        return new SolveConfig(
                config.engine(),
                candidateTimeout,
                config.moveThreadCount(),
                config.acceptor(),
                spec.seed(),
                steps,
                config.unimprovedSecondsSpentLimit(),
                config.direction(),
                config.useFrozenAssignments(),
                Map.copyOf(parameters));
    }

    private CandidateResult runCandidate(
            ScheduleProblem problem,
            SolveConfig config,
            SolverAdapter adapter,
            CandidateSpec spec,
            long candidateDeadlineNanos) {
        long beginNanos = System.nanoTime();
        try {
            TenantContext.setTenantId(problem.tenantId());
            long remainingNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(1),
                    candidateDeadlineNanos - System.nanoTime());
            int boundedSeconds = (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remainingNanos));
            SolveConfig bounded = new SolveConfig(
                    config.engine(),
                    boundedSeconds,
                    config.moveThreadCount(),
                    config.acceptor(),
                    config.randomSeed(),
                    config.maximumStepCount(),
                    config.unimprovedSecondsSpentLimit(),
                    config.direction(),
                    config.useFrozenAssignments(),
                    config.parameters());
            ScheduleSolution solution = adapter.solve(problem, bounded);
            long measuredMs = elapsedMillis(beginNanos);
            if (solution == null) {
                return CandidateResult.failed(spec, SolvePortfolioFailureReason.ENGINE_ERROR,
                        measuredMs, null, false, "求解器返回空结果");
            }
            long slotWaitMs = slotWaitMs(solution);
            SolvePortfolioCandidateStatus status = statusOf(solution);
            SolvePortfolioFailureReason failureReason = status == SolvePortfolioCandidateStatus.SUCCEEDED
                    ? null : failureOf(solution, status);
            return new CandidateResult(spec, status, failureReason, solution,
                    solution.score() == null ? null : solution.score().hardScore(),
                    solution.score() == null ? null : solution.score().softScore(),
                    measuredMs, solution.assignments().size(),
                    solution.metrics() == null ? null : solution.metrics().terminationReason(),
                    slotWaitMs, false, solution.message());
        } catch (OutOfMemoryError error) {
            LOGGER.error("portfolio 候选 {} 内存溢出", spec.profile().label(), error);
            return CandidateResult.failed(spec, SolvePortfolioFailureReason.OOM,
                    elapsedMillis(beginNanos), null, true, "候选内存溢出");
        } catch (Throwable throwable) {
            LOGGER.warn("portfolio 候选 {} 失败（已隔离）", spec.profile().label(), throwable);
            return CandidateResult.failed(spec, SolvePortfolioFailureReason.ENGINE_ERROR,
                    elapsedMillis(beginNanos), null, false, throwable.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private static SolvePortfolioCandidateStatus statusOf(ScheduleSolution solution) {
        String status = solution.status() == null ? "" : solution.status().toUpperCase(java.util.Locale.ROOT);
        if ("TIMEOUT".equals(status)) {
            return SolvePortfolioCandidateStatus.TIMEOUT;
        }
        if ("SOLVED".equals(status)) {
            return SolvePortfolioCandidateStatus.SUCCEEDED;
        }
        return SolvePortfolioCandidateStatus.FAILED;
    }

    private static SolvePortfolioFailureReason failureOf(
            ScheduleSolution solution, SolvePortfolioCandidateStatus status) {
        if (status == SolvePortfolioCandidateStatus.TIMEOUT) {
            return SolvePortfolioFailureReason.TIMEOUT;
        }
        Object fallback = solution.metrics() == null || solution.metrics().extra() == null
                ? null : solution.metrics().extra().get("engineFallbackReason");
        if ("SOLVER_BUSY".equals(fallback)) {
            return SolvePortfolioFailureReason.SOLVER_BUSY;
        }
        return SolvePortfolioFailureReason.ENGINE_ERROR;
    }

    private static long slotWaitMs(ScheduleSolution solution) {
        if (solution.metrics() == null || solution.metrics().extra() == null) {
            return 0;
        }
        Object value = solution.metrics().extra().get("slotWaitMs");
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static CandidateResult selectWinner(List<CandidateResult> results, int expectedTaskCount) {
        Comparator<CandidateResult> comparator = Comparator
                .comparing((CandidateResult result) -> result.hardScore() == null
                        ? BigDecimal.valueOf(Long.MIN_VALUE) : result.hardScore(), Comparator.reverseOrder())
                .thenComparing(result -> result.softScore() == null
                        ? BigDecimal.valueOf(Long.MIN_VALUE) : result.softScore(), Comparator.reverseOrder())
                .thenComparingLong(CandidateResult::durationMs)
                .thenComparingLong(result -> result.spec().seed())
                .thenComparingInt(result -> result.spec().profile().ordinal());
        return results.stream()
                .filter(result -> result.status() == SolvePortfolioCandidateStatus.SUCCEEDED)
                .filter(result -> result.solution() != null)
                .filter(result -> result.hardScore() != null
                        && result.hardScore().compareTo(BigDecimal.ZERO) == 0)
                .filter(result -> result.assignedTaskCount() == expectedTaskCount)
                .min(comparator)
                .orElse(null);
    }

    private static CandidateResult selectBestEffort(List<CandidateResult> results) {
        Comparator<CandidateResult> byScore = Comparator
                .comparing((CandidateResult result) -> result.hardScore() == null
                        ? BigDecimal.valueOf(Long.MIN_VALUE) : result.hardScore(), Comparator.reverseOrder())
                .thenComparing(result -> result.softScore() == null
                        ? BigDecimal.valueOf(Long.MIN_VALUE) : result.softScore(), Comparator.reverseOrder())
                .thenComparing(Comparator.comparingInt(CandidateResult::assignedTaskCount).reversed())
                .thenComparingLong(CandidateResult::durationMs)
                .thenComparingLong(result -> result.spec().seed());
        return results.stream()
                .filter(result -> result.solution() != null)
                .min(byScore)
                .orElse(null);
    }

    private static String failureReason(List<CandidateResult> results, int started) {
        List<CandidateResult> running = results.stream()
                .filter(result -> result.spec().order() < started)
                .toList();
        if (running.isEmpty()) {
            return "ENGINE_ERROR";
        }
        boolean allTimeout = running.stream()
                .allMatch(result -> result.failureReason() == SolvePortfolioFailureReason.TIMEOUT);
        if (allTimeout) {
            return "TIMEOUT";
        }
        boolean allBusy = running.stream()
                .allMatch(result -> result.failureReason() == SolvePortfolioFailureReason.SOLVER_BUSY);
        return allBusy ? "SOLVER_BUSY" : "ENGINE_ERROR";
    }

    private static Map<String, Object> observation(
            int candidateCount,
            CandidateResult winner,
            boolean degraded,
            SolvePortfolioDegradedReason degradedReason,
            List<CandidateResult> ordered,
            List<Map<String, Object>> candidateMaps,
            boolean truncated,
            ScoreTimelines.Timeline timeline) {
        long slotWaitMs = ordered.stream()
                .mapToLong(CandidateResult::slotWaitMs)
                .max()
                .orElse(0);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("enabled", true);
        observation.put("candidateCount", candidateCount);
        observation.put("winnerProfile", winner == null ? null : winner.spec().profile().label());
        observation.put("degraded", degraded);
        observation.put("degradedReason", degradedReason == null ? null : degradedReason.name());
        // 仅候选抢槽的等待；生命周期在落库前把工作队列等待累加进来。
        observation.put("slotWaitMs", slotWaitMs);
        observation.put("candidates", candidateMaps);
        observation.put("timeToBestMs", timeline.timeToBestMs());
        observation.put("scoreTimeline", pointsToMaps(timeline.points()));
        observation.put("truncated", truncated);
        observation.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        return observation;
    }

    private static List<Map<String, Object>> pointsToMaps(List<ScorePoint> points) {
        List<Map<String, Object>> maps = new ArrayList<>(points.size());
        for (ScorePoint point : points) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("elapsedMs", point.elapsedMs());
            map.put("step", point.step());
            map.put("hardScore", point.hardScore());
            map.put("softScore", point.softScore());
            maps.add(map);
        }
        return maps;
    }

    /**
     * 单个候选转 {@code SolvePortfolioCandidate} 的 JSON 形态（C54：字段扁平，不得增删）。
     */
    private static Map<String, Object> candidateMap(CandidateResult result, boolean winner) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profile", result.spec().profile().label());
        map.put("seed", result.spec().seed());
        map.put("status", result.status().name());
        map.put("durationMs", result.status() == SolvePortfolioCandidateStatus.REJECTED
                ? null : result.durationMs());
        map.put("hardScore", result.hardScore());
        map.put("softScore", result.softScore());
        map.put("terminationReason", result.terminationReason());
        map.put("winner", winner);
        map.put("failureReason", result.failureReason() == null ? null : result.failureReason().name());
        return map;
    }

    private static void publishProgress(
            PortfolioContext context, int completed, int started, CandidateResult result) {
        ScheduleService.ProgressSink sink = context.progressSink();
        if (sink == null) {
            return;
        }
        double progress = 0.10 + 0.80 * completed / Math.max(1, started);
        String bestScore = result.solution() == null || result.solution().score() == null
                ? null : result.solution().score().scoreText();
        try {
            sink.update("SOLVING", Math.min(0.95, progress), bestScore);
        } catch (RuntimeException exception) {
            LOGGER.debug("portfolio 进度回调失败（忽略）", exception);
        }
    }

    private static ExecutorService newExecutor(int parallel, long solveTaskId) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "aps-portfolio-" + solveTaskId + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallel, parallel, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static void shutdown(ExecutorService executor, boolean cancel) {
        if (cancel) {
            executor.shutdownNow();
        } else {
            executor.shutdown();
        }
        try {
            long waitMillis = cancel ? CANCEL_AWAIT_MILLIS : NORMAL_AWAIT_MILLIS;
            if (!executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("portfolio 候选线程未在 {}ms 内退出，强制 shutdownNow", waitMillis);
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long beginNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
    }

    /**
     * 运行上下文。
     *
     * @param solveTaskId 求解任务主键（线程命名/日志）
     * @param taskCount 题面任务数（用于 gating 与完整性判定）
     * @param stopSignal 取消/超时信号
     * @param progressSink 进度回调
     */
    public record PortfolioContext(
            long solveTaskId,
            int taskCount,
            ScheduleService.StopSignal stopSignal,
            ScheduleService.ProgressSink progressSink) {
    }

    /**
     * 候选执行结果。
     */
    private record CandidateResult(
            CandidateSpec spec,
            SolvePortfolioCandidateStatus status,
            SolvePortfolioFailureReason failureReason,
            ScheduleSolution solution,
            BigDecimal hardScore,
            BigDecimal softScore,
            long durationMs,
            int assignedTaskCount,
            String terminationReason,
            long slotWaitMs,
            boolean outOfMemory,
            String message) {

        static CandidateResult rejected(CandidateSpec spec) {
            return new CandidateResult(spec,
                    SolvePortfolioCandidateStatus.REJECTED,
                    SolvePortfolioFailureReason.REJECTED_BY_ADMISSION,
                    null, null, null, 0, 0, null, 0, false,
                    "候选被 gating 拒绝");
        }

        static CandidateResult aborted(CandidateSpec spec, long durationMs) {
            return new CandidateResult(spec,
                    SolvePortfolioCandidateStatus.TIMEOUT,
                    SolvePortfolioFailureReason.TIMEOUT,
                    null, null, null, durationMs, 0, null, 0, false,
                    "候选未在预算内完成");
        }

        static CandidateResult failed(
                CandidateSpec spec,
                SolvePortfolioFailureReason reason,
                long durationMs,
                ScheduleSolution solution,
                boolean outOfMemory,
                String message) {
            return new CandidateResult(spec,
                    SolvePortfolioCandidateStatus.FAILED,
                    reason, solution,
                    solution == null || solution.score() == null ? null : solution.score().hardScore(),
                    solution == null || solution.score() == null ? null : solution.score().softScore(),
                    durationMs,
                    solution == null ? 0 : solution.assignments().size(),
                    solution == null || solution.metrics() == null ? null : solution.metrics().terminationReason(),
                    solution == null ? 0 : SolvePortfolioCoordinator.slotWaitMs(solution),
                    outOfMemory, message);
        }
    }
}
