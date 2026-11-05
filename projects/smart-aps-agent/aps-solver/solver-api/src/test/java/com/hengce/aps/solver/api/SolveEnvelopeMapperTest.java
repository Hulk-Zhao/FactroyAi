package com.hengce.aps.solver.api;

import com.hengce.aps.common.constant.ApsConstants;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SolveEnvelopeMapper} 的映射单元测试，不依赖求解器实际运行。
 */
class SolveEnvelopeMapperTest {

    @Test
    void problemUsesDefaultTenantAndSandboxWhenAbsent() {
        SolveRequest request = new SolveRequest("T-1", null, null, null, List.of(), Map.of());

        SolveConfig config = SolveEnvelopeMapper.toConfig(request, SolverEngine.TIMEFOLD);
        ScheduleProblem problem = SolveEnvelopeMapper.toProblem(request, config);

        assertEquals(ApsConstants.DEFAULT_TENANT_ID, problem.tenantId());
        assertEquals(ScheduleProblem.DEFAULT_SANDBOX_ID, problem.sandboxId());
        assertNull(problem.solveTaskId());
        assertEquals("FORWARD", problem.direction());
        assertTrue(problem.orders().isEmpty());
        assertTrue(problem.resources().isEmpty());
        assertTrue(problem.frozenAssignments().isEmpty());
        assertTrue(problem.constraints().isEmpty());
    }

    @Test
    void problemKeepsExplicitSandboxAndStrategy() {
        UUID sandboxId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        SolveRequest request = new SolveRequest("T-2", sandboxId, strategyId, "timefold", List.of(), Map.of());

        SolveConfig config = SolveEnvelopeMapper.toConfig(request, SolverEngine.TIMEFOLD);
        ScheduleProblem problem = SolveEnvelopeMapper.toProblem(request, config);

        assertEquals(sandboxId, problem.sandboxId());
        assertEquals(strategyId, problem.strategyId());
    }

    @Test
    void configUsesDefaultsWhenParametersMissing() {
        SolveRequest request = new SolveRequest("T-3", null, null, null, List.of(), null);

        SolveConfig config = SolveEnvelopeMapper.toConfig(request, SolverEngine.TIMEFOLD);

        assertEquals("timefold", config.engine());
        assertEquals(60, config.timeoutSeconds());
        assertEquals(0, config.moveThreadCount(),
                "未传 moveThreadCount 时必须保持 0（引擎默认），不得在入口补成 CPU-1");
        assertEquals("LATE_ACCEPTANCE", config.acceptor());
        assertEquals(0L, config.randomSeed());
        assertEquals(0L, config.maximumStepCount());
        assertEquals(10, config.unimprovedSecondsSpentLimit());
        assertEquals("FORWARD", config.direction());
        assertTrue(config.useFrozenAssignments());
        assertTrue(config.parameters().isEmpty());
    }

    @Test
    void configPromotesKnownParameters() {
        Map<String, Object> parameters = Map.of(
                "timeoutSeconds", 30,
                "moveThreadCount", 4,
                "acceptor", "TABU_SEARCH",
                "randomSeed", 42,
                "maxSteps", 1000,
                "unimprovedSecondsSpentLimit", 5,
                "direction", "backward",
                "useFrozenAssignments", false);
        SolveRequest request = new SolveRequest("T-4", null, null, "ortools", List.of(), parameters);

        SolveConfig config = SolveEnvelopeMapper.toConfig(request, SolverEngine.ORTOOLS);

        assertEquals("ortools", config.engine());
        assertEquals(30, config.timeoutSeconds());
        assertEquals(4, config.moveThreadCount());
        assertEquals("TABU_SEARCH", config.acceptor());
        assertEquals(42L, config.randomSeed());
        assertEquals(1000L, config.maximumStepCount());
        assertEquals(5, config.unimprovedSecondsSpentLimit());
        assertEquals("BACKWARD", config.direction());
        assertFalse(config.useFrozenAssignments());
        assertSame(parameters, config.parameters());
    }

    @Test
    void resultMapsSolvedAssignmentsToLegacyEnvelope() {
        OffsetDateTime start = OffsetDateTime.of(2026, 1, 2, 0, 30, 0, 0, ZoneOffset.UTC);
        TaskAssignment assignment = new TaskAssignment(
                null,
                UUID.randomUUID(),
                "SO-1",
                10,
                "粗车",
                null,
                UUID.randomUUID(),
                "R-001",
                List.of(),
                start,
                start,
                start,
                start.plusHours(4),
                BigDecimal.TEN,
                1,
                "PLANNED",
                false,
                1);
        ScheduleSolution solution = new ScheduleSolution(
                "SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ONE, "0hard/1soft"),
                List.of(assignment),
                List.of(),
                new SolveMetrics(100L, "TIME_LIMIT", List.of(), 1, Map.of()),
                "ok");
        SolveRequest request = new SolveRequest("T-5", null, null, null, List.of(), Map.of());

        SolveResult result = SolveEnvelopeMapper.toResult(request, solution);

        assertEquals("T-5", result.taskNo());
        assertEquals(SolveStatus.SUCCESS, result.status());
        assertEquals("ok", result.message());
        assertEquals(1, result.tasks().size());
        ScheduledTask task = result.tasks().get(0);
        assertEquals("SO-1", task.orderNo());
        assertEquals("R-001", task.resourceCode());
        assertEquals(start, task.startTime());
        assertEquals(start.plusHours(4), task.endTime());
    }

    @Test
    void resultMapsFailureAndTimeoutToFailed() {
        SolveRequest request = new SolveRequest("T-6", null, null, null, List.of(), Map.of());
        ScheduleSolution solution = new ScheduleSolution(
                "TIMEOUT",
                null,
                List.of(),
                List.of(),
                new SolveMetrics(0L, "TIME_LIMIT", List.of(), 0, Map.of()),
                "timeout");

        SolveResult result = SolveEnvelopeMapper.toResult(request, solution);

        assertEquals(SolveStatus.FAILED, result.status());
        assertTrue(result.tasks().isEmpty());
        assertEquals("timeout", result.message());
    }

    @Test
    void resultHandlesNullSolution() {
        SolveRequest request = new SolveRequest("T-7", null, null, null, List.of(), Map.of());

        SolveResult result = SolveEnvelopeMapper.toResult(request, null);

        assertEquals(SolveStatus.FAILED, result.status());
        assertTrue(result.tasks().isEmpty());
    }
}
