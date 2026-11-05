package com.hengce.aps.solver.timefold.constraint;

import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 8 条核心软约束判定与惩罚量测试：每条规则 1 个正例（触发）与 1 个反例（不触发）。
 */
class SoftConstraintPredicatesTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 1, 2, 0, 0);

    @Test
    void minTardinessPenalizesLateTaskAndIgnoresEarlyTask() {
        TaskPlan late = task(0, 3600);
        late.setDueDate(LocalDate.of(2026, 1, 1));
        late.setStartTime(DAY_START);

        TaskPlan early = task(0, 3600);
        early.setDueDate(LocalDate.of(2026, 1, 3));
        early.setStartTime(DAY_START.plusHours(1));

        assertEquals(3600 / 60, SoftConstraintPredicates.tardinessMinutes(late));
        assertEquals(0, SoftConstraintPredicates.tardinessMinutes(early));
    }

    @Test
    void eddPenalizesLaterDueBeforeEarlierDueAndAcceptsAscendingDue() {
        TaskPlan previous = task(0, 1800);
        previous.setDueDate(LocalDate.of(2026, 1, 5));
        TaskPlan current = task(0, 1800);
        current.setDueDate(LocalDate.of(2026, 1, 3));
        assertTrue(SoftConstraintPredicates.dueOrderViolated(previous, current));

        TaskPlan ordered = task(0, 1800);
        ordered.setDueDate(LocalDate.of(2026, 1, 6));
        assertFalse(SoftConstraintPredicates.dueOrderViolated(current, ordered));
    }

    @Test
    void sptPenalizesLongTaskBeforeShortTask() {
        TaskPlan longTask = task(0, 7200);
        TaskPlan shortTask = task(0, 1800);
        assertTrue(SoftConstraintPredicates.sptViolated(longTask, shortTask));
        assertFalse(SoftConstraintPredicates.sptViolated(shortTask, longTask));
    }

    @Test
    void sameProductPenalizesFamilyChange() {
        TaskPlan previous = task(0, 1800);
        previous.setProductFamily("FAMILY-A");
        TaskPlan current = task(0, 1800);
        current.setProductFamily("FAMILY-B");
        assertTrue(SoftConstraintPredicates.familyChangeViolated(previous, current));

        TaskPlan same = task(0, 1800);
        same.setProductFamily("FAMILY-A");
        assertFalse(SoftConstraintPredicates.familyChangeViolated(previous, same));
    }

    @Test
    void minSetupPenalizesSetupSeconds() {
        TaskPlan withSetup = task(600, 1800);
        TaskPlan withoutSetup = task(0, 1800);
        assertEquals(10, SoftConstraintPredicates.setupMinutes(withSetup));
        assertEquals(0, SoftConstraintPredicates.setupMinutes(withoutSetup));
    }

    @Test
    void balanceLoadUsesSquaredHoursAndIgnoresIdleResource() {
        assertEquals(4, SoftConstraintPredicates.loadHoursSquared(7200));
        assertEquals(0, SoftConstraintPredicates.loadHoursSquared(0));
    }

    @Test
    void priorityRespectPenalizesLowPriorityBeforeUrgent() {
        TaskPlan low = task(0, 1800);
        low.setPriority("LOW");
        TaskPlan urgent = task(0, 1800);
        urgent.setPriority("URGENT");
        assertTrue(SoftConstraintPredicates.priorityViolated(low, urgent));
        assertFalse(SoftConstraintPredicates.priorityViolated(urgent, low));
    }

    @Test
    void minMakespanCountsMinutesBetweenEarliestStartAndLatestEnd() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 2, 8, 0);
        LocalDateTime end = start.plusHours(4);
        assertEquals(240, SoftConstraintPredicates.makespanMinutes(start, end));
        assertEquals(0, SoftConstraintPredicates.makespanMinutes(start, start));
    }

    private static TaskPlan task(long setupSeconds, long runSeconds) {
        return new TaskPlan(
                UUID.randomUUID(),
                "SO-TEST",
                10,
                "工序 10",
                null,
                RESOURCE_ID,
                List.of(),
                BigDecimal.ONE,
                setupSeconds,
                runSeconds,
                false,
                null,
                null);
    }
}
