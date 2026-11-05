package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 跨窗口下界与 A 固定时间锚的回归用例。
 */
class HorizonAlignmentTest {

    private static final int SOLVE_SECONDS = 2;

    private static final LocalDateTime BASE = LocalDateTime.now(ZoneOffset.UTC)
            .withMinute(0).withSecond(0).withNano(0);

    /**
     * E1：窗口外的 FROZEN 前序已不在题面（无冻结分配、无依赖边）时，订单级下界必须拦住
     * 求解器把后续工序排到它完工之前。
     */
    @Test
    void crossWindowLowerBoundKeepsSuccessorAfterFrozenEnd() {
        UUID resourceId = UUID.randomUUID();
        LocalDateTime frozenEnd = BASE.plusHours(6);
        SchedulingOrder order = order(resourceId, frozenEnd);
        ScheduleProblem problem = problem(List.of(order), resource(resourceId, "R-1"), BASE);

        ScheduleSolution solution = new TimefoldSolverAdapter().solve(problem, config());

        assertEquals("SOLVED", solution.status(), solution.message());
        assertEquals(0L, solution.score().hardScore().longValueExact(), solution.message());
        assertEquals(2, solution.assignments().size());
        for (TaskAssignment assignment : solution.assignments()) {
            assertTrue(assignment.setupStartTime().isAfter(frozenEnd.atOffset(ZoneOffset.UTC))
                            || assignment.setupStartTime().equals(frozenEnd.atOffset(ZoneOffset.UTC)),
                    "窗口内工序开始时间不得早于窗口外 FROZEN 任务的结束时间: "
                            + assignment.setupStartTime());
        }
    }

    /**
     * A：题面给定基准时间后，同一份数据构建出的开始时间候选完全一致，不再随真实时刻漂移。
     */
    @Test
    void fixedBaseTimeProducesIdenticalStartTimeRange() {
        UUID resourceId = UUID.randomUUID();
        ScheduleProblem problem = problem(
                List.of(order(resourceId, null)), resource(resourceId, "R-1"), BASE);

        SchedulePlan first = TimefoldModelBuilder.build(problem);
        SchedulePlan second = TimefoldModelBuilder.build(problem);

        assertEquals(first.getStartTimes(), second.getStartTimes());
        assertEquals(BASE, first.getStartTimes().get(0));
    }

    private static SchedulingOrder order(UUID resourceId, LocalDateTime notBefore) {
        List<SchedulingOperation> operations = List.of(
                operation(10, resourceId, List.of()),
                operation(20, resourceId, List.of(new OperationDependency(10, "FS", 0))));
        return new SchedulingOrder(
                UUID.randomUUID(),
                "SO-LOWER-BOUND",
                "MAT-LB",
                null,
                BigDecimal.valueOf(12),
                LocalDate.now(ZoneOffset.UTC).plusDays(5),
                "NORMAL",
                "UNFROZEN",
                UUID.randomUUID(),
                operations,
                notBefore);
    }

    private static SchedulingOperation operation(
            int operationIndex, UUID resourceId, List<OperationDependency> predecessors) {
        return new SchedulingOperation(
                operationIndex,
                "OP-" + operationIndex,
                "工序 " + operationIndex,
                null,
                BigDecimal.valueOf(600),
                BigDecimal.valueOf(600),
                resourceId,
                null,
                List.of(),
                false,
                predecessors);
    }

    private static SchedulingResource resource(UUID resourceId, String code) {
        return new SchedulingResource(
                resourceId,
                code,
                "PRIMARY",
                null,
                "SECONDS_PER_UNIT",
                BigDecimal.ONE,
                null,
                BigDecimal.ONE,
                null,
                "ACTIVE");
    }

    private static ScheduleProblem problem(
            List<SchedulingOrder> orders,
            SchedulingResource resource,
            LocalDateTime baseTime) {
        return new ScheduleProblem(
                "tenant-demo",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                orders,
                List.of(resource),
                List.of(),
                List.of(),
                List.of(),
                baseTime,
                List.of());
    }

    private static SolveConfig config() {
        return new SolveConfig(
                "timefold",
                SOLVE_SECONDS,
                1,
                "LATE_ACCEPTANCE",
                1L,
                0L,
                0,
                "FORWARD",
                true,
                Map.of());
    }
}
