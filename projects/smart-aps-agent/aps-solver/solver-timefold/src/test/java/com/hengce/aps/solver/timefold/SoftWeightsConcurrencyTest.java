package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.ScorePoint;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.task.SolveRetryReasons;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.ConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软约束权重的并发隔离取证（真跑 Timefold，不用 mock）。
 *
 * <p>A0 起 {@link SoftConstraintWeights} 改为线程私有（{@link ThreadLocal}），
 * {@link TimefoldSolverAdapter} 不再使用进程级 {@code SOLVER_LOCK}，改用实例级公平信号量
 * 控制求解槽位。本用例用同一实例上<b>同时</b>发出的两个求解请求（不同权重）验证：</p>
 * <ul>
 *   <li>两个同时发出的请求分别等于各自单跑应得的分数（短题面，分数确定）；</li>
 *   <li>长求解进行中，快求解不会被阻塞，两段求解区间在时间上重叠，且各算各的权重；</li>
 *   <li>槽位显式配置为 1、等待上限 2 秒时，后到方拿到明确的 {@code FAILED} +
 *       {@code engineFallbackReason=SOLVER_BUSY}（不可重试），而不是静默算歪；</li>
 *   <li>已建成的求解会话在权重被清空后仍按建厂时的权重计分（权重只在建厂时读取）；</li>
 *   <li>求解过程填充 {@code metrics.scoreHistory}（时间戳 + 分数）。</li>
 * </ul>
 *
 * <p>确定分题面（{@link #pinnedProblem()}）：单个冻结任务固定在 2026-01-05 08:00、加工 30 分钟，
 * 订单交期 2026-01-04，超期恒为 510 分钟、makespan 恒为 30 分钟：
 * 权重 0 → {@code -480 = -(16 * 30)}；权重 40 → {@code -20580 = -(40 * 510 + 6 * 30)}。</p>
 */
class SoftWeightsConcurrencyTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000005a1");

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000005b1");

    private static final LocalDateTime PINNED_START = LocalDateTime.of(2026, 1, 5, 8, 0);

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 1, 4);

    private static final LocalTime DUE_TIME = LocalTime.of(23, 59, 59);

    private static final long BASELINE_SOFT_SCORE = -480L;

    private static final long WEIGHTED_SOFT_SCORE = -20580L;

    /**
     * 默认槽位数必须至少为 2：同 JVM 内两个求解任务要能真正并行。
     */
    @Test
    void defaultAdapterMustAllowAtLeastTwoConcurrentSolvers() {
        TimefoldSolverAdapter adapter = new TimefoldSolverAdapter();
        assertTrue(adapter.solverSlotPermits() >= 2,
                "默认求解槽位不足 2，无法同 JVM 并行求解: " + adapter.solverSlotPermits());
    }

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    /**
     * 两个同时发出的请求、权重不同：各自得分必须与单跑一致。
     */
    @Test
    void concurrentRequestsWithDifferentWeightsMustNotShareState() throws Exception {
        long expectedBaseline = singleRunSoftScore(weights(0));
        long expectedWeighted = singleRunSoftScore(weights(40));

        Run[] runs = runBoth(pinnedProblem(), "baseline", fastConfig(Map.of("softWeights", weights(0))),
                "weighted", fastConfig(Map.of("softWeights", weights(40))));
        Run baseline = runs[0];
        Run weighted = runs[1];

        assertEquals("SOLVED", baseline.solution().status());
        assertEquals("SOLVED", weighted.solution().status());
        assertEquals(expectedBaseline, baseline.softScore().longValueExact(),
                "权重 0 的并发请求被另一套权重污染: " + describe(baseline, weighted));
        assertEquals(expectedWeighted, weighted.softScore().longValueExact(),
                "权重 40 的并发请求被另一套权重污染: " + describe(baseline, weighted));
        assertEquals(0L, baseline.hardScore().longValueExact());
        assertEquals(0L, weighted.hardScore().longValueExact());
        assertTrue(baseline.weightsCleared() && weighted.weightsCleared(),
                "并发结束后各求解线程的权重必须清空");
    }

    /**
     * 一个请求带权重、另一个请求不带权重同时发出：不带权重的必须仍是 M1 仅硬约束（{@code softScore=0}）。
     */
    @Test
    void concurrentWeightedAndOmittedRequestsMustNotLeakResidue() throws Exception {
        long expectedWeighted = singleRunSoftScore(weights(40));

        Run[] runs = runBoth(pinnedProblem(), "weighted", fastConfig(Map.of("softWeights", weights(40))),
                "omitted", fastConfig(Map.of()));
        Run weighted = runs[0];
        Run omitted = runs[1];

        assertEquals("SOLVED", weighted.solution().status());
        assertEquals("SOLVED", omitted.solution().status());
        assertEquals(expectedWeighted, weighted.softScore().longValueExact());
        assertEquals(0L, omitted.softScore().longValueExact(),
                "未带 softWeights 的并发请求拿到了别人的软得分，说明权重跨线程泄漏: "
                        + describe(weighted, omitted));
        assertTrue(weighted.weightsCleared() && omitted.weightsCleared(),
                "并发结束后各求解线程的权重必须清空");
    }

    /**
     * 并行取证：同一实例上先发起一个 3 秒长求解，200ms 后发起一个不带权重的快求解。
     * A0 去锁后快求解不再被挡，两段求解区间必须重叠；各自只用自己的权重（快求解 {@code softScore==0}）。
     */
    @Test
    void longAndFastSolvesMustRunConcurrentlyWithIsolatedWeights() throws Exception {
        TimefoldSolverAdapter adapter = new TimefoldSolverAdapter();
        CountDownLatch holderStarted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Run> longRun = pool.submit(() -> solveInThread(adapter, "weighted",
                    busyProblem(), timedConfig(Map.of("softWeights", weights(40)), 3), holderStarted));
            holderStarted.countDown();
            // 等长求解真正进入求解阶段；ThreadLocal 已不可从外部线程观测，改用固定 200ms 起跑窗。
            Thread.sleep(200L);
            Run probe = solveInThread(adapter, "omitted-probe", busyProblem(), fastConfig(Map.of()),
                    new CountDownLatch(0));
            Run weighted = longRun.get(60, TimeUnit.SECONDS);

            assertEquals("SOLVED", weighted.solution().status(),
                    "长求解失败: " + weighted.solution().message());
            assertTrue(weighted.softScore().longValueExact() < 0,
                    "带权重的长求解必须产生负软得分（交期早于排产起点，逾期不可避免）: "
                            + describe(weighted, probe));
            assertEquals("SOLVED", probe.solution().status(),
                    "快求解失败: " + probe.solution().message());
            assertEquals(0L, probe.softScore().longValueExact(),
                    "不带权重的快求解在长求解进行中拿到了软得分，说明权重跨线程泄漏: "
                            + describe(weighted, probe));
            long overlapNanos = Math.min(weighted.endNanos(), probe.endNanos())
                    - Math.max(weighted.beginNanos(), probe.beginNanos());
            assertTrue(probe.beginNanos() < weighted.endNanos(),
                    "快求解在长求解结束后才开始（重叠 " + overlapNanos / 1_000_000L
                            + "ms），说明去锁失败或槽位仍为 1: " + describe(weighted, probe));
            assertTrue(probe.durationMillis() < 1500L,
                    "快求解被阻塞了 " + probe.durationMillis() + "ms，说明仍在串行化: "
                            + describe(weighted, probe));
            assertTrue(weighted.weightsCleared() && probe.weightsCleared(),
                    "求解结束必须清空当前线程的权重");
        }
    }

    /**
     * 争用取证：显式 1 个槽位、等待上限 2 秒，持槽方 4 秒；后到方必须拿到明确的 {@code FAILED} +
     * {@code engineFallbackReason=SOLVER_BUSY}，且该原因不可重试。
     */
    @Test
    void contendedRequestMustFailWithNonRetryableSolverBusy() throws Exception {
        TimefoldSolverAdapter adapter = new TimefoldSolverAdapter(1, 2);
        CountDownLatch started = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Run> holder = pool.submit(() -> solveInThread(adapter, "weighted-holder",
                    busyProblem(), timedConfig(Map.of("softWeights", weights(40)), 4), started));
            started.countDown();
            Thread.sleep(200L);
            Run rejected = solveInThread(adapter, "omitted-rejected", busyProblem(),
                    timedConfig(Map.of(), 2), new CountDownLatch(0));
            Run holderRun = holder.get(60, TimeUnit.SECONDS);

            assertEquals("FAILED", rejected.solution().status(),
                    "后到方应明确失败而不是静默求解: " + rejected.solution().message());
            assertTrue(rejected.solution().message().contains("求解器正被其他求解任务占用"),
                    "失败原因必须指向槽位争用，实际: " + rejected.solution().message());
            Object reason = rejected.solution().metrics().extra().get("engineFallbackReason");
            assertEquals(TimefoldSolverAdapter.SOLVER_BUSY, reason,
                    "槽位等待超时必须标记为 SOLVER_BUSY，实际: " + reason);
            assertFalse(SolveRetryReasons.isRetryable(TimefoldSolverAdapter.SOLVER_BUSY),
                    "SOLVER_BUSY 属于容量拒绝，不得进入重试白名单");
            assertEquals("SOLVED", holderRun.solution().status(),
                    "持槽方必须正常完成: " + holderRun.solution().message());
            assertTrue(holderRun.softScore().longValueExact() < 0,
                    "持槽方必须按自己的权重计分: " + describe(holderRun, rejected));
        }
    }

    /**
     * 观测取证：{@code BestSolutionChangedEvent} 必须填充 {@code metrics.scoreHistory}（时间戳 + 分数）。
     */
    @Test
    void scoreHistoryMustBeFilledDuringSolve() {
        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(busyProblem(), timedConfig(Map.of("softWeights", weights(40)), 2));

        assertEquals("SOLVED", solution.status(), "求解失败: " + solution.message());
        assertFalse(solution.metrics().scoreHistory().isEmpty(),
                "scoreHistory 为空，说明未监听 BestSolutionChangedEvent");
        long previous = -1L;
        for (ScorePoint point : solution.metrics().scoreHistory()) {
            assertTrue(point.elapsedMs() >= previous, "scoreHistory 时间戳必须单调不减");
            assertTrue(point.hardScore() != null && point.softScore() != null,
                    "scoreHistory 采样点必须带硬/软分");
            previous = point.elapsedMs();
        }
    }

    /**
     * 机制证据：权重在 {@code buildSolver()}（即 {@code defineConstraints}）时固化进约束流，
     * 之后改动注册表不会影响已建成的求解会话。因此“并发不串”的边界是“同一时刻只有一个会话
     * 处在 activate→solve 区间”，由适配器的静态锁保证。
     */
    @Test
    void solverSessionMustFreezeWeightsAtBuildTime() {
        SoftConstraintWeights.activate(weights(40));
        SolverFactory<SchedulePlan> factory = createFactory();
        Solver<SchedulePlan> solver = factory.buildSolver();
        SoftConstraintWeights.disable();

        SchedulePlan plan = TimefoldModelBuilder.build(pinnedProblem());
        SchedulePlan best = solver.solve(plan);
        ScheduleSolution solution = TimefoldModelBuilder.toSolution(
                best, 0L, ConstraintJustifications.from(best), "TERMINATED", false);

        assertEquals(-20580L, solution.score().softScore().longValueExact(),
                "注册表在求解前被清空，但已建成的会话仍应按建厂时的权重计分");
        assertFalse(SoftConstraintWeights.isEnabled());
    }

    /**
     * 先单跑一遍取期望值：串行执行，注册表在调用前后都为空。
     */
    private static long singleRunSoftScore(Map<String, Integer> weights) {
        ScheduleSolution solution = new TimefoldSolverAdapter()
                .solve(pinnedProblem(), fastConfig(Map.of("softWeights", weights)));
        assertEquals("SOLVED", solution.status());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertFalse(SoftConstraintWeights.isEnabled(), "单跑结束必须回到关闭状态");
        long score = solution.score().softScore().longValueExact();
        assertEquals(weights.get("MIN_TARDINESS") == 0 ? BASELINE_SOFT_SCORE : WEIGHTED_SOFT_SCORE, score);
        return score;
    }

    /**
     * 两个线程在同一个闸门后同时起跑：同一份题面，只有权重（或有无权重）不同。
     * 共享同一个适配器实例（对应 Spring 单例），槽位默认 >= 2，因此两个请求可以真正并行。
     */
    private static Run[] runBoth(ScheduleProblem problem, String firstLabel, SolveConfig firstConfig,
            String secondLabel, SolveConfig secondConfig) throws Exception {
        TimefoldSolverAdapter adapter = new TimefoldSolverAdapter();
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Run> first = pool.submit(
                    () -> solveInThread(adapter, firstLabel, problem, firstConfig, gate));
            Future<Run> second = pool.submit(
                    () -> solveInThread(adapter, secondLabel, problem, secondConfig, gate));
            gate.countDown();
            return new Run[] {first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)};
        }
    }

    /**
     * 单线程求解，闸门放行后才真正调用适配器，保证两个请求的起点尽量接近。
     */
    private static Run solveInThread(TimefoldSolverAdapter adapter, String label, ScheduleProblem problem,
            SolveConfig config, CountDownLatch gate) throws InterruptedException {
        gate.await();
        long beginNanos = System.nanoTime();
        ScheduleSolution solution = adapter.solve(problem, config);
        return new Run(label, solution, beginNanos, System.nanoTime(),
                !SoftConstraintWeights.isEnabled());
    }

    private static String describe(Run first, Run second) {
        return "[" + first.label() + " " + first.durationMillis() + "ms status=" + first.solution().status()
                + " soft=" + first.softScore() + "] vs [" + second.label() + " " + second.durationMillis()
                + "ms status=" + second.solution().status() + " soft=" + second.softScore() + "]";
    }

    private static SolverFactory<SchedulePlan> createFactory() {
        TerminationConfig termination = new TerminationConfig().withSpentLimit(Duration.ofSeconds(1));
        termination.setStepCountLimit(10);
        SolverConfig config = new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                .withConstraintProviderClass(ApsConstraintProvider.class)
                .withRandomSeed(1L)
                .withTerminationConfig(termination);
        return SolverFactory.create(config);
    }

    /**
     * 快跑配置：超时 5 秒、步数上限 10，用于确定分题面的单跑与并发对比。
     */
    private static SolveConfig fastConfig(Map<String, Object> parameters) {
        return new SolveConfig("timefold", 5, 1, "LATE_ACCEPTANCE", 1L, 10L, 0,
                "FORWARD", true, parameters);
    }

    /**
     * 长跑配置：给定超时、不限步数，用于串行化取证。
     */
    private static SolveConfig timedConfig(Map<String, Object> parameters, int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 1L, 0L, 0,
                "FORWARD", true, parameters);
    }

    /**
     * 权重：{@code MIN_TARDINESS=0} 一套；{@code MIN_TARDINESS=40} 一套（其余四条各下调 10 补齐），
     * 两套合计均为 100。
     */
    private static Map<String, Integer> weights(int tardiness) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("MIN_TARDINESS", tardiness);
        if (tardiness == 0) {
            weights.put("EDD", 16);
            weights.put("SPT", 8);
            weights.put("SAME_PRODUCT_CONTINUOUS", 12);
            weights.put("MIN_SETUP_TIME", 12);
            weights.put("BALANCE_LOAD", 12);
            weights.put("PRIORITY_RESPECT", 24);
            weights.put("MIN_MAKESPAN", 16);
        } else {
            weights.put("EDD", 6);
            weights.put("SPT", 8);
            weights.put("SAME_PRODUCT_CONTINUOUS", 12);
            weights.put("MIN_SETUP_TIME", 12);
            weights.put("BALANCE_LOAD", 2);
            weights.put("PRIORITY_RESPECT", 14);
            weights.put("MIN_MAKESPAN", 6);
        }
        return weights;
    }

    /**
     * 确定分题面：单个冻结任务固定在一个时间点，分数与搜索过程无关。
     */
    private static ScheduleProblem pinnedProblem() {
        SchedulingResource resource = new SchedulingResource(
                RESOURCE_ID, "R-CONC", "PRIMARY", null, "SECONDS_PER_UNIT",
                BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE");
        SchedulingOperation operation = new SchedulingOperation(
                10, "OP-10", "工序 10", null, BigDecimal.valueOf(1800), BigDecimal.ZERO,
                RESOURCE_ID, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(
                ORDER_ID, "SO-CONC", "MAT-CONC", "FAM-CONC", BigDecimal.ONE,
                DUE_DATE, "NORMAL", "FROZEN", UUID.randomUUID(), List.of(operation));
        OffsetDateTime start = PINNED_START.atOffset(ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(
                null, ORDER_ID, "SO-CONC", 10, "工序 10", null,
                RESOURCE_ID, "R-CONC", List.of(),
                start, start, start, start.plusSeconds(1800),
                BigDecimal.ONE, 0, "FROZEN", true, 1);
        return new ScheduleProblem(
                "tenant-conc", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                List.of(order), List.of(resource), List.of(frozen), List.of());
    }

    /**
     * 长跑题面：3 个订单、每个 1 道工序（未冻结），全部只能排在同一台主资源上。
     * 有可移动的开工时间，3 秒超时会被用满（这正是串行化取证需要的持锁时长）；工序间无依赖，保证可行。
     */
    private static ScheduleProblem busyProblem() {
        List<SchedulingResource> resources = List.of(
                new SchedulingResource(RESOURCE_ID, "R-CONC-1", "PRIMARY", null, "SECONDS_PER_UNIT",
                        BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE"));
        List<SchedulingOrder> orders = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID orderId = UUID.fromString("00000000-0000-0000-0000-0000000006b" + index);
            List<SchedulingOperation> operations = List.of(
                    new SchedulingOperation(10, "OP-10", "工序 10", null,
                            BigDecimal.valueOf(1800), BigDecimal.ZERO, RESOURCE_ID, null, List.of(), false));
            orders.add(new SchedulingOrder(orderId, "SO-CONC-" + index, "MAT-CONC-" + index, "FAM-CONC",
                    BigDecimal.ONE, DUE_DATE, "NORMAL", "UNFROZEN", UUID.randomUUID(), operations));
        }
        return new ScheduleProblem(
                "tenant-conc", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                orders, resources, List.of(), List.of());
    }

    /**
     * 一次求解的观测记录：标签、结果、起止纳秒、求解线程的权重是否已清空。
     */
    private record Run(String label, ScheduleSolution solution, long beginNanos, long endNanos,
                       boolean weightsCleared) {

        BigDecimal softScore() {
            return solution.score() == null ? null : solution.score().softScore();
        }

        BigDecimal hardScore() {
            return solution.score() == null ? null : solution.score().hardScore();
        }

        long durationMillis() {
            return (endNanos - beginNanos) / 1_000_000L;
        }
    }
}
