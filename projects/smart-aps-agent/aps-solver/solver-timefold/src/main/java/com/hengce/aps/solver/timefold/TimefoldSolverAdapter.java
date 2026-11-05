package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedEntityPlacerConfig;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedValuePlacerConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.MoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.list.ListChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.list.ListRuinRecreateMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.list.SubListChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.list.SubListSwapMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.phase.PhaseConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.ScorePoint;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.ConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Timefold Solver 适配器。
 *
 * <p>M1-SE-03 在 M1-SE-02 的同步求解之上接入 C12 约束能力：资源可用时段、工序依赖与滞后、
 * 换型矩阵、辅助资源占用，并在结果中输出降级标记与约束归因。
 * M2 在此接入 8 条核心软约束：{@code SolveConfig.parameters} 携带 {@code softWeights} 时，
 * 必须在 {@code SolverFactory.create(...)} <b>之前</b> {@link SoftConstraintWeights#activate(Map)}
 * ——{@link ApsConstraintProvider} 在构造时读取 {@code isEnabled()}，工厂建完再激活无效。</p>
 *
 * <p><b>并发保护（A0 去进程锁）</b>：软约束权重已改为线程私有（{@link SoftConstraintWeights} 的
 * {@link ThreadLocal}），因此不再需要进程级 {@code ReentrantLock}。本适配器改用实例级公平
 * {@link Semaphore} 控制并发求解槽位：默认并发度 {@code max(2, min(可用核数-2, 4))}，
 * 可通过 {@code aps.solve.max-concurrent-solvers} 显式指定，等待上限由
 * {@code aps.solve.solve-slot-wait-seconds} 控制（且不超过本次请求超时）。
 * 拿不到槽位时返回 {@code FAILED} 且 {@code engineFallbackReason=SOLVER_BUSY}，
 * 该原因<b>不可重试</b>，避免锁竞争被误判为 {@code ENGINE_ERROR} 后触发 30 秒重试风暴。</p>
 *
 * <p><b>缺字段 → 规则静默不生效</b>（输入由 {@code TimefoldModelBuilder} 从订单级回填到任务级）：</p>
 *
 * <table border="1">
 *   <caption>软约束输入字段与缺字段行为</caption>
 *   <tr><th>规则</th><th>输入字段</th><th>缺字段时期望行为</th></tr>
 *   <tr><td>MIN_TARDINESS</td><td>{@code dueDate}</td><td>无交期不产生超期惩罚（该任务规则不生效）</td></tr>
 *   <tr><td>EDD</td><td>{@code dueDate}（相邻两任务）</td><td>任一方无交期则判定为不违反（不罚）</td></tr>
 *   <tr><td>SPT</td><td>{@code runSeconds}</td><td>不依赖新增字段</td></tr>
 *   <tr><td>SAME_PRODUCT_CONTINUOUS</td><td>{@code productFamily}（相邻两任务）</td>
 *       <td>任一方为空则判定为不违反，同品连续规则不生效</td></tr>
 *   <tr><td>MIN_SETUP_TIME</td><td>{@code setupSeconds}</td><td>不依赖新增字段；无换型为 0</td></tr>
 *   <tr><td>BALANCE_LOAD</td><td>{@code runSeconds}</td><td>不依赖新增字段</td></tr>
 *   <tr><td>PRIORITY_RESPECT</td><td>{@code priority}（相邻两任务）</td>
 *       <td>为空按 {@code NORMAL}，仅保持最轻档位，不会误罚</td></tr>
 *   <tr><td>MIN_MAKESPAN</td><td>{@code startTime/endTime}</td><td>不依赖新增字段</td></tr>
 * </table>
 */
public class TimefoldSolverAdapter implements SolverAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimefoldSolverAdapter.class);

    /**
     * 引擎编码（外部小写）。
     */
    private static final String ENGINE_CODE = "timefold";

    /**
     * 失败兜底说明。
     */
    private static final String FAILED_MESSAGE = "Timefold 求解失败";

    /**
     * 软约束权重在 {@code SolveConfig.parameters} 中的键，与 {@code ScheduleService} 对齐。
     */
    private static final String SOFT_WEIGHTS_KEY = "softWeights";

    /**
     * 归因扣分与软分不一致的固定日志标记；出现即为缺陷，不可当作噪音过滤。
     */
    private static final String JUSTIFICATION_SCORE_MISMATCH = "JUSTIFICATION_SCORE_MISMATCH";

    /**
     * 求解槽位不可用的降级原因：不可重试（{@code SolveRetryReasons} 白名单不含它）。
     */
    public static final String SOLVER_BUSY = "SOLVER_BUSY";

    /**
     * 未显式配置时的求解槽位等待上限（秒）。
     */
    private static final int DEFAULT_SLOT_WAIT_SECONDS = 5;

    /**
     * 自动并发度上限。
     */
    private static final int MAX_AUTO_SLOTS = 4;

    /**
     * 自动并发度下限（A0 要求同 JVM 至少可并行两个求解任务）。
     */
    private static final int MIN_AUTO_SLOTS = 2;

    /**
     * 评分轨迹最多保留的采样点，避免长求解把未落库的指标无限膨胀。
     */
    private static final int MAX_SCORE_POINTS = 1000;

    /**
     * 步数模式下 {@code timeoutSeconds} 缺失（{@code <=0}）时的时间护栏秒数。
     *
     * <p>不能取 0：{@code spentLimit=0} 等于无上限。Timefold 的 {@code stepCountLimit}
     * 只在 step 结束时才自增判定，而 Local Search 的单步在严格局部最优（零接受）时
     * 依赖 forager 的 {@code isQuitEarly()}，可能永不返回；单步内只有时间型 termination
     * 会被检查。因此步数模式必须叠加一个非零时间上限作为安全护栏。</p>
     */
    private static final int DEFAULT_STEP_MODE_SPENT_LIMIT_SECONDS = 60;

    /**
     * 求解槽位（实例级、公平信号量）；只覆盖 activate → 建工厂 → 求解 → disable，
     * 不再跨实例、跨任务互斥。
     */
    private final Semaphore solverSlots;

    /**
     * 槽位总数（诊断用）。
     */
    private final int slotPermits;

    /**
     * 等待求解槽位的最长秒数；实际取值还会被本次请求超时截断。
     */
    private final int slotWaitSeconds;

    /**
     * 默认构造：并发度自动解析，等待上限 {@value #DEFAULT_SLOT_WAIT_SECONDS} 秒。
     */
    public TimefoldSolverAdapter() {
        this(0, DEFAULT_SLOT_WAIT_SECONDS);
    }

    /**
     * 指定求解槽位配置。
     *
     * @param maxConcurrentSolvers 并发求解槽位数；{@code <=0} 表示按 CPU 自动解析
     * @param slotWaitSeconds 等待槽位上限（秒）
     */
    public TimefoldSolverAdapter(int maxConcurrentSolvers, int slotWaitSeconds) {
        int permits = maxConcurrentSolvers > 0 ? maxConcurrentSolvers : autoSlotPermits();
        this.solverSlots = new Semaphore(permits, true);
        this.slotPermits = permits;
        this.slotWaitSeconds = Math.max(1, slotWaitSeconds);
        LOGGER.info("求解槽位初始化: permits={} slotWaitSeconds={} mode={}",
                permits, this.slotWaitSeconds, maxConcurrentSolvers > 0 ? "EXPLICIT" : "AUTO");
    }

    /**
     * 自动并发度：{@code max(2, min(核数-2, 4))}，保证同 JVM 至少两个求解任务可并行。
     *
     * @return 槽位数
     */
    static int autoSlotPermits() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(MIN_AUTO_SLOTS, Math.min(processors - 2, MAX_AUTO_SLOTS));
    }

    /**
     * 当前实例的槽位数（供诊断/测试读取）。
     *
     * @return 槽位数
     */
    int solverSlotPermits() {
        return slotPermits;
    }

    /**
     * 扩展移动算子开关：{@code parameters.moveSelectors=subchain} 时启用工序链整体搬动算子。
     */
    private static final String MOVE_SELECTORS_PARAM = "moveSelectors";

    /**
     * 诊断开关：{@code parameters.scoreCurve=true} 时，每出现一个更优解就输出一条
     * {@code SCORE_CURVE elapsedMs=... score=...} 日志，用于观察改进曲线。
     */
    private static final String SCORE_CURVE_PARAM = "scoreCurve";

    /**
     * 判定“到达时限”的容差毫秒数：Timefold 的 {@code spentLimit} 结束时刻含少量收尾开销，
     * 实测为 +1.7%（60s 跑成 61.0s），容差取 1 秒足以区分“跑满时限”与“提前终止”。
     */
    private static final long TIME_LIMIT_TOLERANCE_MILLIS = 1000L;

    /**
     * Timefold 多线程求解的企业版服务类；社区版未打包该实现。
     *
     * <p>2.8.0 社区版下，{@code withMoveThreadCount} 只要不是 {@code NONE}（含 {@code "1"}、{@code "AUTO"}）
     * 都会在 {@code buildSolver()} 抛
     * {@code IllegalStateException: A commercial feature "Multi-threaded solving" was requested...}。
     * 契约的 {@code moveThreadCount=0} 默认取 {@code CPU-1}，若原样传进 {@code SolverConfig}，
     * 会让“不带 parameters 的默认请求”直接失败，因此必须做能力探测而非盲目透传。</p>
     */
    private static final String ENTERPRISE_SOLVER_SERVICE_CLASS =
            "ai.timefold.solver.enterprise.core.DefaultTimefoldSolverEnterpriseService";

    /**
     * 当前发行版是否具备多线程求解能力（社区版为 {@code false}）。
     */
    private static final boolean MULTI_THREADED_SOLVING_AVAILABLE = enterpriseServiceAvailable();

    @Override
    public SolverEngine engine() {
        return SolverEngine.TIMEFOLD;
    }

    @Override
    public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
        Objects.requireNonNull(problem, "problem 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        long beginNanos = System.nanoTime();
        long slotAcquireBeginNanos = System.nanoTime();
        int waitSeconds = Math.min(slotWaitSeconds, Math.max(1, config.timeoutSeconds()));
        boolean acquired;
        try {
            acquired = solverSlots.tryAcquire(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return withSlotWait(failedSolution("等待求解槽位被中断，未执行求解",
                    elapsedMillis(beginNanos), SOLVER_BUSY), elapsedMillis(slotAcquireBeginNanos));
        }
        if (!acquired) {
            return withSlotWait(failedSolution("求解器正被其他求解任务占用，等待 " + waitSeconds
                    + " 秒仍未释放，请稍后重试", elapsedMillis(beginNanos), SOLVER_BUSY),
                    elapsedMillis(slotAcquireBeginNanos));
        }
        long slotWaitMs = elapsedMillis(slotAcquireBeginNanos);
        try {
            Map<String, Integer> weights = resolveSoftWeights(config);
            if (!weights.isEmpty()) {
                SoftConstraintWeights.activate(weights);
            } else {
                // 显式关闭而不是"假设上次已清空"：只要本次请求没带权重，就必须回到 M1 仅硬约束，
                // 不能因线程局部残留而静默沿用别人的权重。
                SoftConstraintWeights.disable();
                LOGGER.debug("未提供 softWeights，本次求解仅硬约束");
            }
            if (problem.orders().isEmpty() && problem.frozenAssignments().isEmpty()) {
                return withSlotWait(emptySolution(beginNanos, config), slotWaitMs);
            }
            SchedulePlan plan = TimefoldModelBuilder.build(problem);
            if (plan.getTasks().isEmpty()) {
                return withSlotWait(emptySolution(beginNanos, config), slotWaitMs);
            }
            SolverFactory<SchedulePlan> solverFactory = SolverFactory.create(
                    solverConfig(config, plan.isWarmStarted()));
            Solver<SchedulePlan> solver = solverFactory.buildSolver();
            long solveBeginNanos = System.nanoTime();
            List<ScorePoint> scoreHistory = Collections.synchronizedList(new ArrayList<>());
            boolean scoreCurve = scoreCurveEnabled(config);
            solver.addEventListener(event -> recordBestSolution(event, solveBeginNanos, scoreHistory, scoreCurve));
            SchedulePlan best = solver.solve(plan);
            long engineMillis = Duration.ofNanos(System.nanoTime() - solveBeginNanos).toMillis();
            if (best == null) {
                return withSlotWait(failedSolution("Timefold 未返回结果",
                        elapsedMillis(beginNanos)), slotWaitMs);
            }
            long spentLimitMillis = effectiveSpentLimitSeconds(config) * 1000L;
            boolean timeLimitReached = engineMillis + TIME_LIMIT_TOLERANCE_MILLIS >= spentLimitMillis;
            boolean stepMode = config.maximumStepCount() > 0;
            String terminationReason;
            if (timeLimitReached) {
                terminationReason = "TIME_LIMIT";
            } else if (stepMode) {
                // 步数模式且没撞时间护栏：步数先到。SOLVED/TIMEOUT 的状态判定仍由可用性决定。
                terminationReason = "STEP_LIMIT";
            } else {
                terminationReason = "TERMINATED";
            }
            if (stepMode && timeLimitReached) {
                LOGGER.warn("步数模式触发时间护栏: maximumStepCount={} spentLimitSeconds={} engineMs={}"
                                + " —— stepCountLimit 在单步内不生效，已按时间收口",
                        config.maximumStepCount(), effectiveSpentLimitSeconds(config), engineMillis);
            }
            LOGGER.info("Timefold 求解结束: engineMs={} termination={} timeLimitReached={} bestScore={}",
                    engineMillis, terminationReason, timeLimitReached, best.getScore());
            if (best.getScore() != null && best.getScore().hardScore() < 0) {
                LOGGER.warn("Timefold 硬约束违约: score={} justifications={}", best.getScore(),
                        ConstraintJustifications.from(best).stream()
                                .map(item -> item.constraintCode() + "=" + item.description())
                                .toList());
            }
            ScheduleSolution solution = TimefoldModelBuilder.toSolution(
                    best,
                    elapsedMillis(beginNanos),
                    justifications(best, problem.solveTaskId()),
                    terminationReason,
                    timeLimitReached,
                    moveThreadAnnotations(config));
            return withSlotWait(withScoreHistory(solution, scoreHistory), slotWaitMs);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return withSlotWait(failedSolution(exception.getMessage(), elapsedMillis(beginNanos)),
                    slotWaitMs);
        } finally {
            SoftConstraintWeights.disable();
            solverSlots.release();
        }
    }

    /**
     * 记录每次出现更优解的时间戳与分数，填充 {@code SolveMetrics.scoreHistory}，
     * 为 time-to-best 等指标提供最小数据源。
     *
     * <p>诊断开关 {@code parameters.scoreCurve=true} 时同时输出 {@code SCORE_CURVE} 日志；
     * 无论开关是否打开，评分轨迹都会被采集。</p>
     *
     * @param event 更优解事件
     * @param solveBeginNanos 求解开始纳秒
     * @param history 采样点容器（线程安全）
     * @param scoreCurve 是否输出诊断日志
     */
    private static void recordBestSolution(BestSolutionChangedEvent<SchedulePlan> event, long solveBeginNanos,
                                           List<ScorePoint> history, boolean scoreCurve) {
        HardSoftScore score = event.getNewBestSolution().getScore();
        if (score == null) {
            return;
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - solveBeginNanos).toMillis();
        if (history.size() < MAX_SCORE_POINTS) {
            history.add(new ScorePoint(elapsedMs, 0L,
                    BigDecimal.valueOf(score.hardScore()), BigDecimal.valueOf(score.softScore())));
        }
        if (scoreCurve) {
            LOGGER.info("SCORE_CURVE elapsedMs={} score={}", elapsedMs, score);
        }
    }

    /**
     * 在结果 metrics.extra 中补 {@code slotWaitMs}，供 portfolio 观测槽位等待（C54）。
     *
     * @param solution 原始结果
     * @param slotWaitMs 抢槽等待毫秒
     * @return 带 slotWaitMs 的结果
     */
    private static ScheduleSolution withSlotWait(ScheduleSolution solution, long slotWaitMs) {
        SolveMetrics metrics = solution.metrics();
        Map<String, Object> extra = new LinkedHashMap<>();
        if (metrics != null && metrics.extra() != null) {
            extra.putAll(metrics.extra());
        }
        extra.put("slotWaitMs", Math.max(0, slotWaitMs));
        SolveMetrics updated = new SolveMetrics(
                metrics == null ? 0L : metrics.durationMs(),
                metrics == null ? null : metrics.terminationReason(),
                metrics == null ? List.of() : metrics.scoreHistory(),
                metrics == null ? 0 : metrics.assignedTaskCount(),
                extra);
        return new ScheduleSolution(solution.status(), solution.score(), solution.assignments(),
                solution.justifications(), updated, solution.message());
    }

    /**
     * 用采集到的评分轨迹替换引擎中立结果中的空 {@code scoreHistory}，其余字段保持不变。
     *
     * @param solution 原始结果
     * @param history 评分轨迹
     * @return 带评分轨迹的结果
     */
    private static ScheduleSolution withScoreHistory(ScheduleSolution solution, List<ScorePoint> history) {
        if (history.isEmpty()) {
            return solution;
        }
        SolveMetrics metrics = solution.metrics();
        List<ScorePoint> snapshot;
        synchronized (history) {
            snapshot = List.copyOf(history);
        }
        return new ScheduleSolution(solution.status(), solution.score(), solution.assignments(),
                solution.justifications(),
                new SolveMetrics(metrics.durationMs(), metrics.terminationReason(), snapshot,
                        metrics.assignedTaskCount(), metrics.extra()),
                solution.message());
    }

    /**
     * 合并硬约束与软约束归因，并校验软约束重放扣分与最终软分一致。
     *
     * <p>软约束权重在 {@code finally} 中 {@code disable()}，本方法必须在关闭前调用，
     * 否则重放拿不到求解时生效的权重。</p>
     *
     * <p><strong>一致性失败信号：</strong>比对不一致时打 ERROR 级结构化日志并带固定标记
     * {@value #JUSTIFICATION_SCORE_MISMATCH}，字段为 solveTaskId / sumDeduction / expectedSoft / delta。
     * {@code delta = sumDeduction - expectedSoft}。该标记一旦出现即为缺陷（归因口径与目标函数分叉），
     * 不是日志噪音；但归因属于解释层，按契约不阻断求解落库，不抛异常、不改任务状态。</p>
     *
     * @param best 最优解
     * @param solveTaskId 求解任务主键，可空（试算）
     * @return 归因列表，硬约束在前
     */
    private List<ConstraintJustification> justifications(SchedulePlan best, Long solveTaskId) {
        List<ConstraintJustification> hard = ConstraintJustifications.from(best);
        List<ConstraintJustification> soft = SoftConstraintJustifications.from(best);
        if (!soft.isEmpty()) {
            long softScore = best.getScore() == null ? 0L : best.getScore().softScore();
            BigDecimal sumDeduction = soft.stream()
                    .map(ConstraintJustification::deduction)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expectedSoft = BigDecimal.valueOf(-softScore);
            BigDecimal delta = sumDeduction.subtract(expectedSoft);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                LOGGER.error("{} solveTaskId={} sumDeduction={} expectedSoft={} delta={}",
                        JUSTIFICATION_SCORE_MISMATCH, solveTaskId,
                        sumDeduction.toPlainString(), expectedSoft.toPlainString(), delta.toPlainString());
            }
        }
        List<ConstraintJustification> merged = new ArrayList<>(hard.size() + soft.size());
        merged.addAll(hard);
        merged.addAll(soft);
        return List.copyOf(merged);
    }

    /**
     * 从 {@code SolveConfig.parameters} 读取软约束权重。
     *
     * <p>只有存在 {@value #SOFT_WEIGHTS_KEY} 时才启用软约束；缺省即本次仅硬约束，
     * 不在此处构造默认权重（避免"假装有策略"）。权重合法性由
     * {@link SoftConstraintWeights#activate(Map)} 校验（8 条且合计 100）。</p>
     *
     * @param config 求解配置
     * @return 权重映射；空表示本次不启用软约束
     * @throws IllegalArgumentException 权重结构非法
     */
    private Map<String, Integer> resolveSoftWeights(SolveConfig config) {
        Map<String, Object> parameters = config.parameters();
        if (parameters == null) {
            return Map.of();
        }
        Object raw = parameters.get(SOFT_WEIGHTS_KEY);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 必须是规则编码到权重的映射");
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 不允许空规则编码");
            }
            weights.put(String.valueOf(entry.getKey()), toWeight(entry.getKey(), entry.getValue()));
        }
        return weights;
    }

    private int toWeight(Object code, Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 权重非整数: " + code + "=" + text);
            }
        }
        throw new IllegalArgumentException(SOFT_WEIGHTS_KEY + " 权重非数值: " + code + "=" + value);
    }

    SolverConfig solverConfig(SolveConfig config) {
        return solverConfig(config, false);
    }

    /**
     * 本次请求的时间预算（秒）：墙钟模式取 {@code timeoutSeconds} 且至少 1 秒；
     * 步数模式取 {@code timeoutSeconds}，缺失时用 {@value #DEFAULT_STEP_MODE_SPENT_LIMIT_SECONDS} 秒护栏，
     * 绝不返回 0（0 在 Timefold 里等于无上限）。
     *
     * @param config 求解配置
     * @return 时间预算秒数，恒为正
     */
    static int effectiveSpentLimitSeconds(SolveConfig config) {
        if (config.maximumStepCount() <= 0) {
            return Math.max(1, config.timeoutSeconds());
        }
        return config.timeoutSeconds() > 0
                ? config.timeoutSeconds()
                : DEFAULT_STEP_MODE_SPENT_LIMIT_SECONDS;
    }

    /**
     * 构造求解配置；预热初始解完整时跳过构造启发式，直接进入局部搜索。
     *
     * @param config 求解配置
     * @param warmStarted 初始解是否完整来自既有排产结果
     * @return Solver 配置
     */
    SolverConfig solverConfig(SolveConfig config, boolean warmStarted) {
        int seconds = effectiveSpentLimitSeconds(config);
        boolean stepMode = config.maximumStepCount() > 0;
        TerminationConfig termination;
        if (stepMode) {
            // A0 基准复现：步数上限是主终止条件；但必须叠加时间上限做安全护栏——
            // 单步内 {@code stepCountLimit} 不生效，严格局部最优时单步可能永不返回。
            // 语义因此是"步数或时间，先到者停"，而不是退化成无界。
            // 不叠加"无改进"：它同样按 step 结束判定，护栏价值为零且会干扰步数口径。
            termination = new TerminationConfig()
                    .withStepCountLimit((int) Math.min(Integer.MAX_VALUE, config.maximumStepCount()))
                    .withSpentLimit(Duration.ofSeconds(seconds));
        } else {
            termination = new TerminationConfig()
                    .withSpentLimit(Duration.ofSeconds(seconds));
            if (config.unimprovedSecondsSpentLimit() > 0) {
                termination.setUnimprovedSecondsSpentLimit((long) config.unimprovedSecondsSpentLimit());
            }
        }
        ResolvedMoveThreads moveThreads = resolveMoveThreads(config.moveThreadCount());
        if (moveThreads.requested() > 1 && moveThreads.effective() <= 1) {
            LOGGER.warn("moveThreadCount 未被采用: 请求={} 解析={} 生效={} 原因={}",
                    config.moveThreadCount(), moveThreads.resolved(), moveThreads.effectiveText(),
                    moveThreads.reason());
        } else {
            LOGGER.info("Timefold 求解配置: 请求 moveThreadCount={} 解析={} 生效={} termination={} 可用核数={}",
                    config.moveThreadCount(), moveThreads.resolved(),
                    moveThreads.effectiveText() == null ? "DEFAULT" : moveThreads.effectiveText(),
                    termination, moveThreads.processors());
        }
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                .withConstraintProviderClass(ApsConstraintProvider.class)
                .withRandomSeed(config.randomSeed());
        if (moveThreads.effectiveText() != null) {
            // 只有“显式请求了非 0 值”才动这个配置项；未传/0 一律交给引擎默认。
            // 社区版（无 enterprise 实现）对任何非 NONE 值都会 buildSolver 抛异常，绝不透传数值。
            solverConfig.setMoveThreadCount(moveThreads.effectiveText());
        }
        solverConfig.withTerminationConfig(termination);
        List<MoveSelectorConfig> extraMoveSelectors = extraMoveSelectors(config);
        if (warmStarted || !extraMoveSelectors.isEmpty()) {
            List<PhaseConfig> phases = new ArrayList<>(2);
            if (!warmStarted) {
                // 构造启发式不接受“无改进”终止条件，只给它墙钟时限（步数模式下同样按步数）。
                // 注意：显式 phase 列表不会像默认配置那样“为每个实体类各生成一个 CH”，
                // 必须手工补两个——ResourcePlan（list 变量，把任务放进资源列表）与
                // TaskPlan（basic 变量 startTime）；漏掉后者会留下 40 个未初始化实体。
                TerminationConfig chTermination = stepMode
                        ? new TerminationConfig().withStepCountLimit(
                                (int) Math.min(Integer.MAX_VALUE, config.maximumStepCount()))
                                .withSpentLimit(Duration.ofSeconds(seconds))
                        : new TerminationConfig().withSpentLimit(Duration.ofSeconds(seconds));
                phases.add(new ConstructionHeuristicPhaseConfig()
                        .withTerminationConfig(chTermination)
                        .withEntityPlacerConfig(new QueuedValuePlacerConfig()
                                .withEntityClass(ResourcePlan.class)
                                .withMoveSelectorConfig(new ListChangeMoveSelectorConfig())));
                phases.add(new ConstructionHeuristicPhaseConfig()
                        .withTerminationConfig(chTermination)
                        .withEntityPlacerConfig(new QueuedEntityPlacerConfig()
                                .withEntitySelectorConfig(new EntitySelectorConfig(TaskPlan.class))
                                .withMoveSelectorConfigList(List.of(new ChangeMoveSelectorConfig()))));
            }
            LocalSearchPhaseConfig localSearch = new LocalSearchPhaseConfig();
            localSearch.setTerminationConfig(termination);
            if (!extraMoveSelectors.isEmpty()) {
                localSearch.setMoveSelectorConfig(new UnionMoveSelectorConfig(extraMoveSelectors));
            }
            phases.add(localSearch);
            solverConfig.setPhaseConfigList(phases);
            if (warmStarted) {
                LOGGER.info("预热初始解完整，跳过构造启发式，直接局部搜索");
            }
            if (!extraMoveSelectors.isEmpty()) {
                LOGGER.info("启用扩展移动算子: {}", extraMoveSelectors);
            }
        }
        return solverConfig;
    }

    /**
     * 读取 {@code parameters.scoreCurve} 诊断开关，缺省关闭。
     *
     * @param config 求解配置
     * @return 是否输出改进曲线日志
     */
    private static boolean scoreCurveEnabled(SolveConfig config) {
        Map<String, Object> parameters = config.parameters();
        Object raw = parameters == null ? null : parameters.get(SCORE_CURVE_PARAM);
        return raw != null && "true".equalsIgnoreCase(raw.toString());
    }

    /**
     * 读取 {@code parameters.moveSelectors} 的扩展算子配置；缺省返回空列表，保持引擎默认。
     *
     * <p>{@code subchain} = 默认的 change/swap 之上叠加 {@code subListChange} 与
     * {@code subListSwap}，用于整体搬动同一资源上的一段连续工序链。</p>
     *
     * @param config 求解配置
     * @return 扩展算子列表，空表示不覆盖默认配置
     */
    private static List<MoveSelectorConfig> extraMoveSelectors(SolveConfig config) {
        Map<String, Object> parameters = config.parameters();
        Object raw = parameters == null ? null : parameters.get(MOVE_SELECTORS_PARAM);
        if (raw == null) {
            return List.of();
        }
        String tokens = raw.toString().toLowerCase(Locale.ROOT);
        List<MoveSelectorConfig> selectors = new ArrayList<>();
        selectors.add(new ChangeMoveSelectorConfig());
        selectors.add(new SwapMoveSelectorConfig());
        if (tokens.contains("subchain")) {
            selectors.add(new SubListChangeMoveSelectorConfig());
            selectors.add(new SubListSwapMoveSelectorConfig());
        }
        if (tokens.contains("ruin")) {
            // 毁坏-重建：一次拆掉一小段工序再整体插回，用于跳出“单步改不动”的链式局部最优。
            selectors.add(new ListRuinRecreateMoveSelectorConfig()
                    .withMinimumRuinedCount(2)
                    .withMaximumRuinedCount(10));
        }
        return selectors;
    }

    /**
     * 合并移动线程的策略解析：未传/{@code 0} 不设置 {@code moveThreadCount}，走引擎默认；
     * 只有非 {@code 0} 值才尝试应用，并按发行版能力收敛。
     *
     * <p>没有企业版实现时，任何非 {@code NONE} 值（含 {@code "1"}）都会被 Timefold 拒绝，
     * 因此“生效值”统一为 {@code NONE}，并把被忽略的事实与原因写入指标。</p>
     *
     * @param requested 请求值（{@code 0} 表示 CPU-1，见契约 C20 {@code SolveConfig}）
     * @return 解析结果
     */
    ResolvedMoveThreads resolveMoveThreads(int requested) {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (requested <= 0) {
            // 未传/0：不设置 moveThreadCount，完全走引擎默认（社区版默认即单线程）。
            return new ResolvedMoveThreads(requested, Math.max(1, processors - 1), 1, null, processors,
                    "未传/0：不设置 moveThreadCount，走引擎默认（社区版即单线程）");
        }
        int resolved = Math.min(requested, processors);
        if (MULTI_THREADED_SOLVING_AVAILABLE && resolved > 1) {
            return new ResolvedMoveThreads(requested, resolved, resolved,
                    String.valueOf(resolved), processors, "企业版多线程求解可用");
        }
        if (MULTI_THREADED_SOLVING_AVAILABLE) {
            return new ResolvedMoveThreads(requested, resolved, 1, "NONE", processors,
                    "请求单线程，显式设置 NONE");
        }
        return new ResolvedMoveThreads(requested, resolved, 1, "NONE", processors,
                "Timefold 2.8.0 社区版未打包多线程求解（enterprise 服务类缺失），"
                        + "请求值被忽略，显式退回 NONE");
    }

    /**
     * 供响应 {@code metrics} 使用的移动线程口径，便于调用方看到“请求值 vs 生效值”。
     *
     * @param config 求解配置
     * @return 指标片段
     */
    Map<String, Object> moveThreadAnnotations(SolveConfig config) {
        ResolvedMoveThreads moveThreads = resolveMoveThreads(config.moveThreadCount());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("moveThreadCountRequested", moveThreads.requested());
        extra.put("moveThreadCountResolved", moveThreads.resolved());
        extra.put("moveThreadCountEffective",
                moveThreads.effectiveText() == null ? "DEFAULT" : moveThreads.effectiveText());
        extra.put("moveThreadCountApplied", moveThreads.effectiveText() != null);
        extra.put("moveThreadCountIgnored",
                moveThreads.requested() > 0 && moveThreads.resolved() > 1 && moveThreads.effective() <= 1);
        extra.put("multiThreadedAvailable", MULTI_THREADED_SOLVING_AVAILABLE);
        return extra;
    }

    /**
     * 探测企业版服务类是否可加载；加载失败即视为不具备多线程能力。
     *
     * @return 是否支持多线程求解
     */
    private static boolean enterpriseServiceAvailable() {
        try {
            Class.forName(ENTERPRISE_SOLVER_SERVICE_CLASS, false,
                    TimefoldSolverAdapter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private ScheduleSolution emptySolution(long beginNanos, SolveConfig config) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("engine", ENGINE_CODE);
        extra.put("calendarFallback", false);
        extra.put("dependencyFallback", false);
        extra.put("setupMatrixFallback", false);
        extra.putAll(moveThreadAnnotations(config));
        return new ScheduleSolution(
                "SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                List.of(),
                List.of(),
                new SolveMetrics(
                        elapsedMillis(beginNanos),
                        "TIME_LIMIT",
                        List.of(),
                        0,
                        extra),
                "无待排任务");
    }

    private ScheduleSolution failedSolution(String message, long elapsedMs) {
        return failedSolution(message, elapsedMs, null);
    }

    /**
     * 失败结果；{@code fallbackReason} 非空时写入 {@code metrics.extra.engineFallbackReason}，
     * 供上层区分“槽位不可用（不可重试）”与普通引擎异常。
     *
     * @param message 失败说明
     * @param elapsedMs 耗时
     * @param fallbackReason 降级原因，可空
     * @return 失败结果
     */
    private ScheduleSolution failedSolution(String message, long elapsedMs, String fallbackReason) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("engine", ENGINE_CODE);
        if (fallbackReason != null) {
            extra.put("engineFallbackReason", fallbackReason);
        }
        return new ScheduleSolution(
                "FAILED",
                null,
                List.of(),
                List.of(),
                new SolveMetrics(
                        elapsedMs,
                        "TERMINATED",
                        List.of(),
                        0,
                        extra),
                message == null ? FAILED_MESSAGE : message);
    }

    private long elapsedMillis(long beginNanos) {
        return Duration.ofNanos(System.nanoTime() - beginNanos).toMillis();
    }

    /**
     * 移动线程解析结果。
     *
     * @param requested 请求值（{@code 0} 表示契约默认 CPU-1）
     * @param resolved 按契约解析后的线程数
     * @param effective 实际生效线程数（能力缺失时恒为 1）
     * @param effectiveText 传给 {@code SolverConfig} 的文本值（{@code NONE} 或数字）
     * @param processors 可用核数
     * @param reason 生效值说明
     */
    record ResolvedMoveThreads(int requested, int resolved, int effective,
                               String effectiveText, int processors, String reason) {
    }
}
