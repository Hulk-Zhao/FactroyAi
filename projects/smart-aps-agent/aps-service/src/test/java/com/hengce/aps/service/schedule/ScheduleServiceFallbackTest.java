package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.service.strategy.StrategyRuleView;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 引擎降级单测（M2-SE-06）：异常、超时、引擎不可用写降级标记；校验失败不降级。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServiceFallbackTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    @Mock
    private ScheduleProblemAssembler assembler;

    @Mock
    private ScheduleResultWriter resultWriter;

    @Mock
    private SolveTaskRepository solveTaskRepository;

    @Mock
    private StrategyService strategyService;

    @Test
    void engineExceptionShouldWriteEngineFallbackMarker() {
        ScheduleService service = new ScheduleService(List.of(new ThrowingAdapter()), assembler,
                resultWriter, solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                new ThrowingAdapter(), () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertTrue(Boolean.TRUE.equals(outcome.metrics().get("engineFallback")));
        assertEquals("ENGINE_ERROR", outcome.metrics().get("engineFallbackReason"));
        assertEquals(EngineRoute.DETAIL_ENGINE_EXCEPTION, outcome.metrics().get("engineFallbackDetail"));
        assertEquals("HARD_ONLY", outcome.metrics().get("fallbackMode"));
        assertTrue(outcome.metrics().get("fallbackAt") != null);
        assertEquals(0, outcome.metrics().get("persistedTaskCount"),
                "FAILED 未落库时必须显式写 0，不能留 null 被读成“未知”");
        assertEquals(0, outcome.metrics().get("assignedTaskCount"),
                "无结果对象时返回体不含任务，已排位数是确定值 0");
        // 对照：确实“没有”的键仍保持 null，不得一刀切补成 0。
        assertNull(outcome.metrics().get("bestScore"));
    }

    @Test
    void engineTimeoutStatusShouldMapToTimeoutFallback() {
        SolverAdapter timeoutAdapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return new ScheduleSolution("TIMEOUT", null, List.of(), List.of(), metrics(), "time limit");
            }
        };
        ScheduleService service = new ScheduleService(List.of(timeoutAdapter), assembler,
                resultWriter, solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                timeoutAdapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.TIMEOUT, outcome.status());
        assertEquals("TIMEOUT", outcome.metrics().get("engineFallbackReason"));
        assertEquals("HARD_ONLY", outcome.metrics().get("fallbackMode"),
                "一个任务都没排出来时仍是 HARD_ONLY，不能误标 PARTIAL");
        assertEquals(0, outcome.metrics().get("persistedTaskCount"),
                "TIMEOUT 不落库，persistedTaskCount 必须恒为 0");
        assertEquals(0, outcome.metrics().get("assignedTaskCount"),
                "TIMEOUT 时部分解被丢弃，已排位数为 0");
        assertTrue(outcome.tasks().isEmpty(), "TIMEOUT 不得返回部分解");
        // 对照：没有得分就是 null（确实“没有”），不是被补出来的 0。
        assertNull(outcome.metrics().get("bestScore"));
    }

    @Test
    void missingAdapterShouldReportSolverUnavailable() {
        ScheduleService service = new ScheduleService(List.of(), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                null, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertEquals("SOLVER_UNAVAILABLE", outcome.metrics().get("engineFallbackReason"));
        assertEquals(EngineRoute.DETAIL_SOLVER_NOT_ASSEMBLED, outcome.metrics().get("engineFallbackDetail"));
    }

    @Test
    void lockContentionShouldBeDistinguishedFromEngineException() {
        SolverAdapter busyAdapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return new ScheduleSolution("FAILED", null, List.of(), List.of(), metrics(),
                        "求解器正被其他求解任务占用，等待 5 秒仍未释放，请稍后重试");
            }
        };
        ScheduleService service = new ScheduleService(List.of(busyAdapter), assembler,
                resultWriter, solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                busyAdapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertEquals("ENGINE_ERROR", outcome.metrics().get("engineFallbackReason"));
        assertEquals(EngineRoute.DETAIL_LOCK_CONTENTION, outcome.metrics().get("engineFallbackDetail"));
    }

    /**
     * A0：适配器把“槽位等待超时”写进 {@code metrics.extra.engineFallbackReason=SOLVER_BUSY} 时，
     * 上层必须原样保留该原因（不可重试），而不是退回 ENGINE_ERROR 触发 30 秒重试。
     */
    @Test
    void solverBusyReasonFromAdapterShouldBePreservedAndNotRetried() {
        Map<String, Object> busyMetrics = new java.util.LinkedHashMap<>();
        busyMetrics.put("engine", "timefold");
        busyMetrics.put("engineFallbackReason", com.hengce.aps.solver.api.task.SolveRetryReasons.SOLVER_BUSY);
        ScheduleSolution busy = new ScheduleSolution("FAILED", null, List.of(), List.of(),
                new SolveMetrics(500L, "TERMINATED", List.of(), 0, busyMetrics),
                "求解器正被其他求解任务占用，等待 5 秒仍未释放，请稍后重试");
        SolverAdapter adapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return busy;
            }
        };
        ScheduleService service = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertEquals(com.hengce.aps.solver.api.task.SolveRetryReasons.SOLVER_BUSY,
                outcome.metrics().get("engineFallbackReason"));
        assertEquals(EngineRoute.DETAIL_LOCK_CONTENTION, outcome.metrics().get("engineFallbackDetail"));
        assertEquals(Boolean.TRUE, outcome.metrics().get("engineFallback"));
        assertFalse(com.hengce.aps.solver.api.task.SolveRetryReasons.isRetryable(
                com.hengce.aps.solver.api.task.SolveRetryReasons.SOLVER_BUSY),
                "SOLVER_BUSY 不得进入重试白名单");
    }

    @Test
    void integrityGuardFailureShouldNotBeMarkedAsEngineFallback() {
        ScheduleSolution complete = new ScheduleSolution("SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                List.of(), List.of(), metrics(), "ok");
        SolverAdapter adapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return complete;
            }
        };
        ScheduleService service = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());
        when(resultWriter.persist(any(), eq(complete), eq(7L)))
                .thenThrow(new ScheduleIntegrityException(
                        "求解结果落库后整表重叠校验失败（本次写入任务参与的重叠对 1 对）: 11/12@R-1"));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertFalse(Boolean.TRUE.equals(outcome.metrics().get("engineFallback")),
                "完整性守卫不是引擎异常，不得写降级标记");
        assertNull(outcome.metrics().get("engineFallbackReason"));
        assertNull(outcome.metrics().get("engineFallbackDetail"));
        assertEquals(0, outcome.metrics().get("persistedTaskCount"));
        assertTrue(outcome.message().contains("整表重叠校验失败"), outcome.message());
    }

    @Test
    void validationFailureShouldNotWriteFallbackMarker() {
        ScheduleService service = new ScheduleService(List.of(new ThrowingAdapter()), assembler,
                resultWriter, solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any()))
                .thenThrow(new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "租户下没有可用资源，无法排产"));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                new ThrowingAdapter(), () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertTrue(outcome.validationFailure());
        assertTrue(Boolean.TRUE.equals(outcome.metrics().get("validationFailure")));
        assertFalse(Boolean.TRUE.equals(outcome.metrics().get("engineFallback")));
        assertNull(outcome.metrics().get("engineFallbackReason"));
    }

    @Test
    void partialAssignmentsShouldMarkPartialWithoutPersisting() {
        UUID orderId = UUID.randomUUID();
        TaskAssignment partial = new TaskAssignment(null, orderId, "SO-1", 1, "工序1", null, null,
                "R-1", List.of(), null, null, null, null, BigDecimal.ONE, 1, "PLANNED", false, 1);
        ScheduleSolution partialSolution = new ScheduleSolution("FAILED", null, List.of(partial),
                List.of(), metrics(), "引擎异常，仅产出部分解");
        SolverAdapter adapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return partialSolution;
            }
        };
        ScheduleService service = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembledWithOperations(orderId, 2));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status(), "PARTIAL 终态仍是 FAILED，不新增状态值");
        assertEquals("PARTIAL", outcome.metrics().get("fallbackMode"),
                "只有“部分排位”才写 PARTIAL");
        assertEquals(2, outcome.metrics().get("expectedTaskCount"));
        assertEquals(1, outcome.metrics().get("assignedTaskCount"));
        assertEquals(1, outcome.metrics().get("partialAssignedTaskCount"));
        assertEquals(1, outcome.metrics().get("unassignedTaskCount"),
                "未排任务数只出现在 metrics.unassignedTaskCount + errorMessage");
        assertEquals(Boolean.TRUE, outcome.metrics().get("discardedBestSolution"));
        assertEquals(0, outcome.metrics().get("persistedTaskCount"));
        assertTrue(outcome.tasks().isEmpty(), "PARTIAL 返回体 tasks 必须为空，未排任务不得以任务形式出现");
        assertTrue(outcome.message().contains("期望 2"), outcome.message());
        assertTrue(outcome.message().contains("未排位 1"), outcome.message());
        verify(resultWriter, never()).persist(any(), any(), anyLong());
    }

    @Test
    void solvedShouldPersistWithSolveTaskIdAndBestScore() {
        ScheduleSolution solution = new ScheduleSolution("SOLVED",
                new ScoreFact(BigDecimal.ZERO, new BigDecimal("-10"), "0hard/-10soft"),
                List.of(), List.of(), metrics(), "ok");
        SolverAdapter adapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return solution;
            }
        };
        ScheduleService service = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);
        when(assembler.assemble(any(), any())).thenReturn(assembled());
        when(resultWriter.persist(any(), eq(solution), eq(7L)))
                .thenReturn(new ScheduleResultWriter.WriteOutcome(2, 1));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(60),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.SOLVED, outcome.status());
        assertEquals(0L, outcome.scoreHard());
        assertEquals(-10L, outcome.scoreSoft());
        assertEquals("0hard/-10soft", outcome.metrics().get("bestScore"));
        assertEquals(2, outcome.metrics().get("persistedTaskCount"));
        assertEquals(1, outcome.metrics().get("justificationCount"));
        // 对外 metrics 不再输出恒 0 的步数键（Timefold 2.8.0 社区版无步数口径）。
        assertFalse(outcome.metrics().containsKey("stepCount"), "不得输出 stepCount");
        assertFalse(outcome.metrics().containsKey("unimprovedStepCount"),
                "不得输出 unimprovedStepCount");
        assertEquals("TIME_LIMIT", outcome.metrics().get("terminationReason"),
                "terminationReason 等其余键行为不变");
    }

    @Test
    void softWeightsShouldBeInjectedWhenStrategyHasEightSoftRules() {
        UUID strategyId = UUID.randomUUID();
        when(strategyService.get(strategyId)).thenReturn(strategy(strategyId));
        ScheduleService service = new ScheduleService(List.of(), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);

        SolveConfig config = service.toConfig(
                new SolveRequest("T-1", SANDBOX_ID, strategyId, "timefold",
                        List.of("SO-1"), Map.of()),
                SolverEngine.TIMEFOLD);

        @SuppressWarnings("unchecked")
        Map<String, Integer> weights = (Map<String, Integer>) config.parameters()
                .get(ScheduleService.SOFT_WEIGHTS_KEY);
        assertNotNull(weights, "生效策略的软规则权重应注入 SolveConfig.parameters");
        assertEquals(8, weights.size());
        assertEquals(100, weights.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(40, weights.get("MIN_TARDINESS"));
        assertFalse(weights.containsKey("HARD_LOCK"));
    }

    @Test
    void noEffectiveStrategyShouldKeepWeightsAbsent() {
        ScheduleService service = new ScheduleService(List.of(), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);

        SolveConfig config = service.toConfig(request(), SolverEngine.TIMEFOLD);

        assertFalse(config.parameters().containsKey(ScheduleService.SOFT_WEIGHTS_KEY),
                "无生效策略时不应注入软约束权重");
    }

    private static StrategyView strategy(UUID strategyId) {
        String[] codes = {"MIN_TARDINESS", "EDD", "SPT", "SAME_PRODUCT_CONTINUOUS",
                "MIN_SETUP_TIME", "BALANCE_LOAD", "PRIORITY_RESPECT", "MIN_MAKESPAN"};
        int[] weightValues = {40, 15, 10, 10, 10, 10, 5, 0};
        List<StrategyRuleView> rules = new ArrayList<>();
        for (int index = 0; index < codes.length; index++) {
            rules.add(new StrategyRuleView(codes[index], codes[index], "OPTIMIZATION", "SOFT",
                    BigDecimal.valueOf(weightValues[index]), Map.of(), true, index + 1));
        }
        rules.add(new StrategyRuleView("HARD_LOCK", "锁定", "INITIAL", "HARD",
                BigDecimal.ZERO, Map.of(), true, 9));
        return new StrategyView(strategyId, "策略A", "FORWARD", 1, Map.of(), Map.of(),
                null, null, Map.of(), rules, rules.size(), BigDecimal.valueOf(100), 1, null, null);
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 0L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private static SolveRequest request() {
        return new SolveRequest("T-1", SANDBOX_ID, null, "timefold", List.of("SO-1"), Map.of());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembled() {
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(), SANDBOX_ID, null,
                null, "FORWARD", List.of(), List.of(), List.of(), List.of(), List.of());
        return new ScheduleProblemAssembler.AssembledProblem(TENANT_ID, SANDBOX_ID, problem, List.of());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembledWithOperations(UUID orderId,
                                                                                     int operationCount) {
        List<SchedulingOperation> operations = new ArrayList<>();
        for (int index = 1; index <= operationCount; index++) {
            operations.add(new SchedulingOperation(index, "OP-" + index, "工序" + index, null,
                    BigDecimal.TEN, BigDecimal.ZERO, null, null, List.of(), false, List.of()));
        }
        SchedulingOrder order = new SchedulingOrder(orderId, "SO-1", "FG-1", BigDecimal.TEN,
                LocalDate.of(2030, 1, 1), "NORMAL", "UNFROZEN", null, operations);
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(), SANDBOX_ID, null,
                null, "FORWARD", List.of(order), List.of(), List.of(), List.of(), List.of());
        return new ScheduleProblemAssembler.AssembledProblem(TENANT_ID, SANDBOX_ID, problem, List.of(orderId));
    }

    private static SolveMetrics metrics() {
        return new SolveMetrics(10L, "TIME_LIMIT", List.of(), 0, Map.of());
    }

    /**
     * 直接抛异常的引擎替身。
     */
    private static final class ThrowingAdapter implements SolverAdapter {

        @Override
        public SolverEngine engine() {
            return SolverEngine.TIMEFOLD;
        }

        @Override
        public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
            throw new IllegalStateException("timefold crashed");
        }
    }
}
