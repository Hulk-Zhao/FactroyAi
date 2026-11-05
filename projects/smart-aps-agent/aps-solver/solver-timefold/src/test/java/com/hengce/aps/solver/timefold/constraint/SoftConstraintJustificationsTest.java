package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软约束归因重放的单元测试（M2-SE-05）。
 */
class SoftConstraintJustificationsTest {

    private static final UUID RESOURCE_ID = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    @Test
    void shouldReplayTardinessWithRuleCodeWeightAndDeduction() {
        SoftConstraintWeights.activate(weightsWith(ConstraintCodes.MIN_TARDINESS, 100));
        TaskPlan task = task("SO-1", 10, LocalDate.of(2026, 11, 3),
                LocalDateTime.of(2026, 11, 4, 8, 0), 3600);
        long tardinessMinutes = SoftConstraintPredicates.tardinessMinutes(task);

        List<ConstraintJustification> justifications =
                SoftConstraintJustifications.from(plan(task));

        assertEquals(1, justifications.size());
        ConstraintJustification justification = justifications.get(0);
        assertEquals(ConstraintCodes.MIN_TARDINESS, justification.constraintCode());
        assertEquals("SOFT", justification.type());
        assertEquals(Integer.valueOf(100), justification.weight());
        assertEquals(0, BigDecimal.valueOf(tardinessMinutes).multiply(BigDecimal.valueOf(100))
                .compareTo(justification.deduction()));
        assertTrue(justification.description().contains("SO-1/工序10"));
    }

    @Test
    void shouldReplayEddPairAndAttributeBothTasks() {
        SoftConstraintWeights.activate(weightsWith(ConstraintCodes.EDD, 100));
        LocalDateTime base = LocalDateTime.of(2026, 11, 4, 8, 0);
        TaskPlan laterDue = task("SO-1", 10, LocalDate.of(2026, 11, 8), base, 1800);
        TaskPlan earlierDue = task("SO-2", 20, LocalDate.of(2026, 11, 5), base.plusHours(1), 1800);
        resource(laterDue, earlierDue);
        earlierDue.setPreviousTask(laterDue);

        List<ConstraintJustification> justifications =
                SoftConstraintJustifications.from(plan(earlierDue, laterDue));

        assertEquals(1, justifications.size());
        ConstraintJustification justification = justifications.get(0);
        assertEquals(ConstraintCodes.EDD, justification.constraintCode());
        assertEquals(new BigDecimal("100"), justification.deduction());
        assertTrue(justification.description().contains("SO-1/工序10"));
        assertTrue(justification.description().contains("SO-2/工序20"));
    }

    @Test
    void shouldReturnEmptyWhenSoftConstraintsDisabled() {
        SoftConstraintWeights.disable();
        TaskPlan task = task("SO-1", 10, LocalDate.of(2026, 11, 3),
                LocalDateTime.of(2026, 11, 4, 8, 0), 3600);

        assertTrue(SoftConstraintJustifications.from(plan(task)).isEmpty());
    }

    private static Map<String, Integer> weightsWith(String code, int weight) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String rule : ConstraintCodes.SOFT_RULE_CODES) {
            weights.put(rule, rule.equals(code) ? weight : 0);
        }
        return weights;
    }

    private static TaskPlan task(String orderNo, int operationIndex, LocalDate dueDate,
                                 LocalDateTime startTime, long runSeconds) {
        TaskPlan task = new TaskPlan(UUID.randomUUID(), orderNo, operationIndex,
                "工序" + operationIndex, UUID.randomUUID(), RESOURCE_ID, List.of(),
                BigDecimal.ONE, 0, runSeconds, false, null, null);
        task.setDueDate(dueDate);
        task.setStartTime(startTime);
        return task;
    }

    private static void resource(TaskPlan... tasks) {
        ResourcePlan resource = new ResourcePlan(new SchedulingResource(
                RESOURCE_ID, "R1", "PRIMARY", null, "UNIT_PER_HOUR",
                BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE"));
        resource.setTasks(List.of(tasks));
        for (TaskPlan task : tasks) {
            task.setResource(resource);
        }
    }

    private static SchedulePlan plan(TaskPlan... tasks) {
        resource(tasks);
        return new SchedulePlan(List.of(tasks[0].getStartTime()),
                List.of(tasks[0].getResource()), List.of(tasks));
    }
}
