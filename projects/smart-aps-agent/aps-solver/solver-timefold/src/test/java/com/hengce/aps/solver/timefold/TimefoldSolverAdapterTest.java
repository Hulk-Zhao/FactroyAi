package com.hengce.aps.solver.timefold;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveHandle;
import com.hengce.aps.solver.api.SolverEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timefold 适配器的契约与空问题路径测试，不依赖外部数据。
 */
class TimefoldSolverAdapterTest {

    private final TimefoldSolverAdapter adapter = new TimefoldSolverAdapter();

    @Test
    void exposesTimefoldEngine() {
        assertEquals(SolverEngine.TIMEFOLD, adapter.engine());
    }

    @Test
    void emptyProblemReturnsSolvedWithoutAssignments() {
        ScheduleSolution solution = adapter.solve(emptyProblem(), config());

        assertEquals("SOLVED", solution.status());
        assertEquals(0, solution.assignments().size());
        assertEquals(0L, solution.score().hardScore().longValueExact());
        assertEquals(0L, solution.score().softScore().longValueExact());
        assertTrue(solution.message().contains("无待排任务"));
    }

    @Test
    void solveRejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> adapter.solve(null, config()));
        assertThrows(NullPointerException.class, () -> adapter.solve(emptyProblem(), null));
    }

    @Test
    void moveThreadCountNeverBreaksCommunityEdition() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        Map<String, Object> annotations = adapter.moveThreadAnnotations(configWithMoveThreads(7));
        boolean enterprise = Boolean.TRUE.equals(annotations.get("multiThreadedAvailable"));

        for (int requested : new int[] {0, 1, 4, 7, processors + 2}) {
            SolveConfig request = configWithMoveThreads(requested);
            SolverConfig solverConfig = adapter.solverConfig(request);
            // P0 断言：默认（0=不传）与显式值都不能让社区版 buildSolver 抛企业版特性缺失。
            assertDoesNotThrow(() -> SolverFactory.create(solverConfig).buildSolver(),
                    "请求 moveThreadCount=" + requested + " 不得导致 buildSolver 失败");

            if (requested <= 0) {
                // 不设置时 SolverConfig 保持 null，由引擎按缺省处理（社区版即单线程）。
                assertNull(solverConfig.getMoveThreadCount(),
                        "未传/0 不得写入 moveThreadCount，必须走引擎默认");
                continue;
            }
            int resolved = Math.min(requested, processors);
            String expectedText = enterprise && resolved > 1 ? String.valueOf(resolved) : "NONE";
            assertEquals(expectedText, solverConfig.getMoveThreadCount(),
                    "请求=" + requested + " 解析=" + resolved + " 的动作线程文本");
        }
        assertEquals("NONE", adapter.solverConfig(configWithMoveThreads(1)).getMoveThreadCount(),
                "显式请求 1 时社区版退回 NONE（单线程）");
    }

    @Test
    void defaultRequestLeadsNoMoveThreadSettingAndIsNotMarkedIgnored() {
        Map<String, Object> extra = adapter.moveThreadAnnotations(configWithMoveThreads(0));

        assertEquals(0, extra.get("moveThreadCountRequested"));
        assertEquals("DEFAULT", extra.get("moveThreadCountEffective"));
        assertEquals(false, extra.get("moveThreadCountApplied"));
        assertEquals(false, extra.get("moveThreadCountIgnored"), "没传就不存在“被忽略”");
    }

    @Test
    void solutionMetricsExposeMoveThreadCountHonesty() {
        ScheduleSolution solution = adapter.solve(emptyProblem(), configWithMoveThreads(7));
        Map<String, Object> extra = solution.metrics().extra();

        assertEquals(7, extra.get("moveThreadCountRequested"));
        assertEquals("NONE", extra.get("moveThreadCountEffective"));
        if (!Boolean.TRUE.equals(extra.get("multiThreadedAvailable"))) {
            assertTrue(extra.get("moveThreadCountResolved") instanceof Integer resolved && resolved > 1);
            assertTrue((Boolean) extra.get("moveThreadCountIgnored"),
                    "社区版必须如实标注“请求值被忽略”，不能看起来生效了其实没用: " + extra);
        }
    }

    @Test
    void incompleteSolutionAtTimeLimitIsTimeoutNotSolved() {
        UUID resourceId = UUID.randomUUID();
        List<SchedulingOperation> operations = new ArrayList<>();
        for (int index = 1; index <= 120; index++) {
            operations.add(SolverFixtures.operation(index, resourceId));
        }
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(SolverFixtures.order("SO-BIG", operations)),
                List.of(SolverFixtures.resource(resourceId, "R-BIG")),
                List.of());

        ScheduleSolution solution = adapter.solve(problem, config());

        // 1 秒不可能把 120 个任务全部排成可行解：绝不能返回 SOLVED，且必须带硬负分。
        assertNotEquals("SOLVED", solution.status());
        assertTrue(solution.score().hardScore().signum() < 0,
                "未排满的题面必须为硬负分，score=" + solution.score());
    }

    @Test
    void asyncLifecycleRemainsUnimplemented() {
        SolveHandle handle = new SolveHandle(UUID.randomUUID(), SolverEngine.TIMEFOLD, "RUNNING");

        assertThrows(UnsupportedOperationException.class, () -> adapter.submitAsync(emptyProblem(), config()));
        assertThrows(UnsupportedOperationException.class, () -> adapter.terminateEarly(handle));
        assertThrows(UnsupportedOperationException.class, () -> adapter.getProgress(handle));
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.solveIncremental(emptyProblem(), config(), null));
    }

    private static ScheduleProblem emptyProblem() {
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static SolveConfig config() {
        return new SolveConfig(
                "timefold",
                1,
                1,
                "LATE_ACCEPTANCE",
                1L,
                0L,
                0,
                "FORWARD",
                true,
                Map.of());
    }

    private static SolveConfig configWithMoveThreads(int moveThreadCount) {
        return new SolveConfig(
                "timefold",
                1,
                moveThreadCount,
                "LATE_ACCEPTANCE",
                1L,
                0L,
                0,
                "FORWARD",
                true,
                Map.of());
    }
}
