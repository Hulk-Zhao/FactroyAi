package com.hengce.aps.service.schedule.atp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ACCURATE 结论口径：超时不得伪造日期；完整可行解才 VERIFIED；并以参考解校验日期取值。
 */
class AtpAccurateCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalDate REQUESTED = LocalDate.of(2026, 11, 10);

    @Test
    void timeoutWithoutCompleteSolutionReturnsNoDate() {
        UUID atpOrderId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-11-05T00:00:00Z");
        ScheduleSolution partial = new ScheduleSolution("TIMEOUT",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                List.of(assignment(atpOrderId, 1, start, start.plusHours(4))),
                List.of(),
                new SolveMetrics(5000L, "TIME_LIMIT", List.of(), 1, Map.of()),
                null);

        AtpAccurateCalculator.Outcome outcome = AtpAccurateCalculator.evaluate(
                partial, atpOrderId, 2, REQUESTED, 5, 5000L, ZONE);

        assertEquals(AtpStatus.TIMEOUT, outcome.status());
        assertEquals(AtpAccuracy.UNVERIFIED, outcome.accuracy());
        assertNull(outcome.earliestDeliveryDate());
        assertNull(outcome.planTo());
        assertEquals(1, outcome.assignedCount());
        assertEquals(1, outcome.unassignedCount());
        assertTrue(outcome.timedOut());
        assertTrue(outcome.bottlenecks().stream().anyMatch(b -> "SOLVE_TIMEOUT".equals(b.type())));
    }

    @Test
    void solvedSolutionIsVerifiedAndMatchesReferenceCompletion() {
        UUID atpOrderId = UUID.randomUUID();
        OffsetDateTime firstStart = OffsetDateTime.parse("2026-11-05T00:00:00Z");
        OffsetDateTime lastEnd = OffsetDateTime.parse("2026-11-05T12:00:00Z");
        List<TaskAssignment> assignments = List.of(
                assignment(atpOrderId, 1, firstStart, firstStart.plusHours(4)),
                assignment(atpOrderId, 2, firstStart.plusHours(4), lastEnd));

        ScheduleSolution solved = new ScheduleSolution("SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                assignments, List.of(),
                new SolveMetrics(1200L, "TIME_LIMIT", List.of(), 2, Map.of()),
                null);

        AtpAccurateCalculator.Outcome outcome = AtpAccurateCalculator.evaluate(
                solved, atpOrderId, 2, REQUESTED, 5, 1200L, ZONE);

        assertEquals(AtpStatus.OK, outcome.status());
        assertEquals(AtpAccuracy.VERIFIED, outcome.accuracy());
        // 与完整求解结果的参考值一致：假设订单全部工序的最晚结束时间
        assertEquals(lastEnd, outcome.planTo());
        assertEquals(LocalDate.of(2026, 11, 5), outcome.earliestDeliveryDate());
        assertTrue(outcome.suggestions().stream().anyMatch(s -> "LOCK_CAPACITY".equals(s.action())));
    }

    @Test
    void infeasibleSolutionReturnsNoDate() {
        UUID atpOrderId = UUID.randomUUID();
        ScheduleSolution infeasible = new ScheduleSolution("INFEASIBLE",
                new ScoreFact(BigDecimal.valueOf(-1), BigDecimal.ZERO, "-1hard/0soft"),
                List.of(), List.of(),
                new SolveMetrics(3000L, "TIME_LIMIT", List.of(), 0, Map.of()),
                null);

        AtpAccurateCalculator.Outcome outcome = AtpAccurateCalculator.evaluate(
                infeasible, atpOrderId, 2, REQUESTED, 5, 3000L, ZONE);

        assertEquals(AtpStatus.INFEASIBLE, outcome.status());
        assertEquals(AtpAccuracy.UNVERIFIED, outcome.accuracy());
        assertNull(outcome.earliestDeliveryDate());
        assertTrue(outcome.bottlenecks().stream().anyMatch(b -> "INFEASIBLE".equals(b.type())));
    }

    private static TaskAssignment assignment(
            UUID orderId, int operationIndex, OffsetDateTime start, OffsetDateTime end) {
        return new TaskAssignment(null, orderId, "ATP-T1", operationIndex, "OP" + operationIndex,
                null, UUID.randomUUID(), "R01", List.of(), start, start, start, end,
                BigDecimal.TEN, operationIndex, "PLANNED", false, 0);
    }
}
