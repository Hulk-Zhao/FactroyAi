package com.hengce.aps.solver.timefold.model;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.timefold.SolverFixtures;
import com.hengce.aps.solver.timefold.TimefoldModelBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换型矩阵在计划实体上的落地：相邻任务命中矩阵，首任务回退静态换型。
 */
class TaskPlanSetupMatrixTest {

    @Test
    void appliesMatrixForAdjacentTaskAndFallsBackForFirstTask() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-SETUP", List.of(
                SolverFixtures.operation(10, resourceId),
                SolverFixtures.operation(20, resourceId)));
        List<SetupMatrixEntry> matrix = List.of(new SetupMatrixEntry(
                null, "OPERATION_CODE", "OP-10", "OP-20", 1800));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC")),
                List.of(),
                matrix);

        SchedulePlan plan = TimefoldModelBuilder.build(problem);
        TaskPlan first = task(plan, 10);
        TaskPlan second = task(plan, 20);
        LocalDateTime start = SolverFixtures.baseTime(2);
        first.setStartTime(start);
        second.setPreviousTask(first);
        second.setStartTime(start.plusSeconds(7800));

        assertEquals(600L, first.getSetupSeconds());
        assertEquals(1800L, second.getSetupSeconds());
        assertEquals(1800L, Duration.between(
                second.getStartTime(), second.getProcessingStartTime()).getSeconds());
        assertEquals(9000L, Duration.between(
                second.getStartTime(), second.getEndTime()).getSeconds());
    }

    @Test
    void fallsBackToStaticSetupWithoutMatrix() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-SETUP", List.of(
                SolverFixtures.operation(10, resourceId),
                SolverFixtures.operation(20, resourceId)));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC")),
                List.of());

        SchedulePlan plan = TimefoldModelBuilder.build(problem);
        TaskPlan first = task(plan, 10);
        TaskPlan second = task(plan, 20);
        first.setStartTime(SolverFixtures.baseTime(2));
        second.setPreviousTask(first);
        second.setStartTime(SolverFixtures.baseTime(2).plusSeconds(7800));

        assertEquals(600L, second.getSetupSeconds());
        assertTrue(plan.isSetupMatrixFallback());
    }

    private static TaskPlan task(SchedulePlan plan, int operationIndex) {
        return plan.getTasks().stream()
                .filter(task -> task.getOperationIndex() == operationIndex)
                .findFirst()
                .orElseThrow();
    }
}
