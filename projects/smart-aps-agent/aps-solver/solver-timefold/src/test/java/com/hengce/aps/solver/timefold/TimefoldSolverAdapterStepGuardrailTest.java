package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 步数模式的时间护栏回归（安全护栏，防 Local Search 单步永不返回）。
 *
 * <p>根因：Timefold 的 {@code stepCountLimit} 只在 step 结束时自增判定；严格局部最优
 * （每个候选 move 都严格变差、零接受）时，LS 的单步依赖 forager 的 {@code isQuitEarly()}
 * 才退出，可能永不返回。单步内只有时间型 termination 会被检查，因此步数模式必须叠加
 * 时间上限，语义是"步数或时间，先到者停"。</p>
 *
 * <p>最小挂起题面见 {@link #rigidProblem()}：2 个任务、各挂唯一主资源、工期 1800s、
 * 日历窗口从 08:00 起，makespan 已在下界（任何 move 都只会让 makespan 变差）。</p>
 */
class TimefoldSolverAdapterStepGuardrailTest {

    private static final UUID RESOURCE_1 = UUID.fromString("00000000-0000-0000-0000-0000000007a1");

    private static final UUID RESOURCE_2 = UUID.fromString("00000000-0000-0000-0000-0000000007a2");

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 5, 8, 0);

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 1, 9);

    /**
     * 步数模式下 {@code timeoutSeconds} 缺失时的护栏秒数，必须非零。
     */
    private static final int GUARDRAIL_SECONDS = 60;

    /**
     * 每个求解调用的硬超时：护栏正常时约 2 秒返回，这里留 30 秒余量；超时即判失败。
     */
    private static final long WATCHDOG_SECONDS = 30L;

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    /**
     * 配置层：步数模式必须同时带 {@code stepCountLimit} 与 {@code spentLimit}，
     * 且缺失 {@code timeoutSeconds} 时用非零护栏而不是 0（0 = 无上限）。
     */
    @Test
    void stepModeConfigMustAlwaysCarryNonZeroTimeGuardrail() {
        SolveConfig missingTimeout = stepConfig(500L, 0);
        assertEquals(GUARDRAIL_SECONDS, TimefoldSolverAdapter.effectiveSpentLimitSeconds(missingTimeout),
                "步数模式缺失 timeoutSeconds 时必须回落到非零护栏");

        SolverConfig solverConfig = new TimefoldSolverAdapter()
                .solverConfig(missingTimeout, true);
        TerminationConfig termination = solverConfig.getTerminationConfig();
        assertNotNull(termination, "必须显式设置 termination");
        assertEquals(500, termination.getStepCountLimit(), "步数上限必须保留");
        assertNotNull(termination.getSpentLimit(), "步数模式必须叠加 spentLimit 时间护栏");
        assertEquals(GUARDRAIL_SECONDS * 1000L, termination.getSpentLimit().toMillis());
    }

    /**
     * 运行层：严格局部最优（零接受）题面上，100/200/500 三档步数都必须在有限时间内返回，
     * 且 {@code terminationReason} 与实际先到的条件一致、分数与纯墙钟同题一致。
     */
    @Test
    void stepModeMustReturnInFiniteTimeAtStrictLocalOptimum() throws Exception {
        TimefoldSolverAdapter adapter = new TimefoldSolverAdapter();
        int timeoutSeconds = 2;

        ScheduleSolution wallClock = solveWithWatchdog(adapter, rigidProblem(),
                config(0L, timeoutSeconds));
        assertEquals("SOLVED", wallClock.status(), "墙钟基线失败: " + wallClock.message());
        assertEquals(0L, wallClock.score().hardScore().longValueExact());
        long baselineSoft = wallClock.score().softScore().longValueExact();
        long wallSpentMs = wallClock.metrics().durationMs();
        assertTrue(TimefoldSolverAdapter.effectiveSpentLimitSeconds(config(0L, timeoutSeconds))
                * 1000L - 1000L <= wallSpentMs,
                "墙钟基线未跑满预算，无法作为对照: " + wallSpentMs + "ms");
        assertEquals("TIME_LIMIT", wallClock.metrics().terminationReason());

        List<String> evidence = new ArrayList<>();
        for (long steps : new long[] {100L, 200L, 500L}) {
            ScheduleSolution solution = solveWithWatchdog(adapter, rigidProblem(),
                    config(steps, timeoutSeconds));
            long spentMs = solution.metrics().durationMs();
            String reason = solution.metrics().terminationReason();
            evidence.add(steps + "步→" + spentMs + "ms/" + reason);

            assertEquals("SOLVED", solution.status(),
                    "步数 " + steps + " 的求解失败: " + solution.message());
            assertEquals(0L, solution.score().hardScore().longValueExact(),
                    "步数 " + steps + " 出现硬违约: " + evidence);
            assertEquals(baselineSoft, solution.score().softScore().longValueExact(),
                    "步数 " + steps + " 的软分与墙钟基线不一致: " + evidence);

            boolean timeWins = spentMs + 1000L >= timeoutSeconds * 1000L;
            assertEquals(timeWins ? "TIME_LIMIT" : "STEP_LIMIT", reason,
                    "terminationReason 与先到条件不符（timeWins=" + timeWins + "）: " + evidence);
        }

        // 严格局部最优下步数不可能先到（单步内不推进步数），否则说明题面没有复现挂起，
        // 本用例就失去了护栏取证价值。
        boolean guardrailFired = evidence.stream().anyMatch(item -> item.endsWith("TIME_LIMIT"));
        assertTrue(guardrailFired, "三档步数都没触发时间护栏，题面未复现单步挂起: " + evidence);
    }

    /**
     * 步数先到分支：有可改进 move 的题面上，步数会在时间护栏之前用完，必须标 {@code STEP_LIMIT}。
     */
    @Test
    void stepModeMustReportStepLimitWhenStepsFinishFirst() throws Exception {
        ScheduleSolution solution = solveWithWatchdog(new TimefoldSolverAdapter(), movableProblem(),
                config(10L, 5));

        assertEquals("SOLVED", solution.status(), "求解失败: " + solution.message());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertEquals("STEP_LIMIT", solution.metrics().terminationReason(),
                "步数先到时必须标 STEP_LIMIT（实际耗时 " + solution.metrics().durationMs() + "ms）");
        assertTrue(solution.metrics().durationMs() < 4000L,
                "步数先到却在时间护栏附近才返回: " + solution.metrics().durationMs() + "ms");
        assertFalse(SoftConstraintWeights.isEnabled(), "求解结束必须清空权重");
    }

    /**
     * 带硬超时的求解：守护线程 + {@code Future.get}，超时即判失败并中断，
     * 不留会阻止 JVM 退出的非守护空转线程。
     *
     * @param adapter 适配器
     * @param problem 题面
     * @param config 求解配置
     * @return 求解结果
     * @throws Exception 等待被中断等
     */
    private static ScheduleSolution solveWithWatchdog(TimefoldSolverAdapter adapter, ScheduleProblem problem,
                                                    SolveConfig config) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "step-guardrail-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<ScheduleSolution> future = pool.submit(() -> adapter.solve(problem, config));
            try {
                return future.get(WATCHDOG_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                return fail("求解超过 " + WATCHDOG_SECONDS + " 秒未返回：步数模式缺少有效时间护栏（"
                        + describe(config) + "）");
            } catch (ExecutionException failure) {
                throw failure;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String describe(SolveConfig config) {
        return "maximumStepCount=" + config.maximumStepCount()
                + " timeoutSeconds=" + config.timeoutSeconds();
    }

    /**
     * 严格局部最优题面：2 个任务各挂唯一主资源，工期 1800s，日历窗口 [08:00, 18:00)，
     * 两个任务都能放在 08:00，makespan 已在物理下界，任何 move 都严格变差。
     *
     * @return 题面
     */
    private static ScheduleProblem rigidProblem() {
        OffsetDateTime windowStart = BASE_TIME.atOffset(ZoneOffset.UTC);
        OffsetDateTime windowEnd = BASE_TIME.plusHours(10).atOffset(ZoneOffset.UTC);
        List<AvailabilityWindow> windows = List.of(new AvailabilityWindow(windowStart, windowEnd));
        List<SchedulingResource> resources = List.of(
                new SchedulingResource(RESOURCE_1, "R-RIGID-1", "PRIMARY", null, "SECONDS_PER_UNIT",
                        BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE", windows),
                new SchedulingResource(RESOURCE_2, "R-RIGID-2", "PRIMARY", null, "SECONDS_PER_UNIT",
                        BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE", windows));
        List<SchedulingOrder> orders = new ArrayList<>();
        UUID[] resourceIds = {RESOURCE_1, RESOURCE_2};
        for (int index = 0; index < 2; index++) {
            UUID orderId = UUID.fromString("00000000-0000-0000-0000-0000000007b" + index);
            List<SchedulingOperation> operations = List.of(
                    new SchedulingOperation(10, "OP-10", "工序 10", null,
                            BigDecimal.valueOf(1800), BigDecimal.ZERO, resourceIds[index], null,
                            List.of(), false, List.of()));
            orders.add(new SchedulingOrder(orderId, "SO-RIGID-" + index, "MAT-RIGID-" + index,
                    "FAM-RIGID-" + index, BigDecimal.ONE, DUE_DATE, "NORMAL", "UNFROZEN",
                    UUID.randomUUID(), operations, null));
        }
        return new ScheduleProblem("tenant-step-guard", ScheduleProblem.DEFAULT_SANDBOX_ID,
                null, null, "FORWARD", orders, resources, List.of(), List.of(), List.of(),
                BASE_TIME, List.of());
    }

    /**
     * 可改进题面：3 个订单共用一台资源、交期早于基准时间，存在大量可降低逾期的 move，
     * 步数会用完而不是挂在单步里。
     *
     * @return 题面
     */
    private static ScheduleProblem movableProblem() {
        List<SchedulingResource> resources = List.of(
                new SchedulingResource(RESOURCE_1, "R-MOVABLE", "PRIMARY", null, "SECONDS_PER_UNIT",
                        BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE", List.of()));
        List<SchedulingOrder> orders = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID orderId = UUID.fromString("00000000-0000-0000-0000-0000000007c" + index);
            List<SchedulingOperation> operations = List.of(
                    new SchedulingOperation(10, "OP-10", "工序 10", null,
                            BigDecimal.valueOf(1800), BigDecimal.ZERO, RESOURCE_1, null,
                            List.of(), false, List.of()));
            orders.add(new SchedulingOrder(orderId, "SO-MOVABLE-" + index, "MAT-MOVABLE-" + index,
                    "FAM-MOVABLE", BigDecimal.ONE, LocalDate.of(2026, 1, 4), "NORMAL", "UNFROZEN",
                    UUID.randomUUID(), operations, null));
        }
        return new ScheduleProblem("tenant-step-guard", ScheduleProblem.DEFAULT_SANDBOX_ID,
                null, null, "FORWARD", orders, resources, List.of(), List.of(), List.of(),
                BASE_TIME, List.of());
    }

    private static SolveConfig config(long maximumStepCount, int timeoutSeconds) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("softWeights", SoftConstraintWeights.defaults());
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 1L,
                maximumStepCount, 0, "FORWARD", true, parameters);
    }

    private static SolveConfig stepConfig(long maximumStepCount, int timeoutSeconds) {
        return config(maximumStepCount, timeoutSeconds);
    }
}
