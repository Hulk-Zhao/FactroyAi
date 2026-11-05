package com.hengce.aps.service.schedule.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 验证求解接缝的结论翻译，不依赖数据库与 Timefold 实现。
 */
class VerificationSolverTest {

    private static final UUID ORDER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID ORDER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    @Test
    void feasibleResultIsMarkedNotPersisted() {
        VerificationOutcome outcome = solve(solution("SOLVED", 0, "TERMINATED",
                List.of(assignment(ORDER_A, "MO-A", 10), assignment(ORDER_B, "MO-B", 20))));

        assertThat(outcome.status()).isEqualTo(VerificationStatus.FEASIBLE);
        assertThat(outcome.persisted()).isFalse();
        assertThat(outcome.mainPlanWritten()).isFalse();
        assertThat(outcome.assignedTaskCount()).isEqualTo(2);
        assertThat(outcome.unassignedTaskCount()).isZero();
    }

    @Test
    void timeoutIsOutOfBudgetInsteadOfInfeasible() {
        VerificationOutcome outcome = solve(solution("TIMEOUT", 0, "TIME_LIMIT",
                List.of(assignment(ORDER_A, "MO-A", 10))));

        assertThat(outcome.status()).isEqualTo(VerificationStatus.OUT_OF_BUDGET);
        assertThat(outcome.unassignedTaskCount()).isNull();
        assertThat(outcome.message()).contains("预算耗尽不等于不可行");
    }

    @Test
    void solvedButTimeLimitWithMissingAssignmentsIsOutOfBudget() {
        VerificationOutcome outcome = solve(solution("SOLVED", 0, "TIME_LIMIT",
                List.of(assignment(ORDER_A, "MO-A", 10))));

        assertThat(outcome.status()).isEqualTo(VerificationStatus.OUT_OF_BUDGET);
        assertThat(outcome.assignedTaskCount()).isEqualTo(1);
        assertThat(outcome.totalTaskCount()).isEqualTo(2);
    }

    @Test
    void negativeHardScoreIsInfeasible() {
        VerificationOutcome outcome = solve(solution("SOLVED", -5, "TERMINATED",
                List.of(assignment(ORDER_A, "MO-A", 10))));

        assertThat(outcome.status()).isEqualTo(VerificationStatus.INFEASIBLE);
        assertThat(outcome.unassignedTaskCount()).isEqualTo(1);
    }

    @Test
    void missingAdapterIsUnverified() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SolverAdapter> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());

        VerificationOutcome outcome = new VerificationSolver(provider)
                .solve(problem(), config(5));

        assertThat(outcome.status()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(outcome.persisted()).isFalse();
        assertThat(outcome.assignments()).isEmpty();
    }

    @Test
    void persistedFlagCannotBeForged() {
        ScheduleProblem problem = problem();
        assertThatThrownBy(() -> new VerificationOutcome(VerificationStatus.FEASIBLE, "timefold",
                0L, BigDecimal.ZERO, BigDecimal.ZERO, 1L, "TERMINATED", 2, 2, 0, List.of(),
                List.of(), true, false, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已落库");
    }

    private VerificationOutcome solve(ScheduleSolution solution) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SolverAdapter> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return solution;
            }
        }));
        return new VerificationSolver(provider).solve(problem(), config(5));
    }

    private static ScheduleProblem problem() {
        SchedulingOperation first = new SchedulingOperation(10, "OP10", "裁切", null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOperation second = new SchedulingOperation(20, "OP20", "组装", null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOrder orderA = new SchedulingOrder(ORDER_A, "MO-A", "P-A", BigDecimal.ONE,
                LocalDate.of(2026, 11, 10), "NORMAL", "UNFROZEN", null, List.of(first));
        SchedulingOrder orderB = new SchedulingOrder(ORDER_B, "MO-B", "P-B", BigDecimal.ONE,
                LocalDate.of(2026, 11, 10), "NORMAL", "UNFROZEN", null, List.of(second));
        SchedulingResource resource = new SchedulingResource(RESOURCE, "R1", "PRIMARY", null,
                "UNIT_PER_HOUR", BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE");
        return new ScheduleProblem(TENANT, ScheduleProblem.DEFAULT_SANDBOX_ID, null, null,
                "FORWARD", List.of(orderA, orderB), List.of(resource), List.of(), List.of(),
                List.of(), LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 0, "LATE_ACCEPTANCE", 0L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private static ScheduleSolution solution(String status, int hardScore, String termination,
                                             List<TaskAssignment> assignments) {
        return new ScheduleSolution(status,
                new ScoreFact(BigDecimal.valueOf(hardScore), BigDecimal.ZERO, hardScore + "soft0"),
                assignments, List.of(),
                new SolveMetrics(120L, termination, List.of(), assignments.size(), Map.of()), null);
    }

    private static TaskAssignment assignment(UUID orderId, String orderNo, int operationIndex) {
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
        return new TaskAssignment(null, orderId, orderNo, operationIndex, "OP",
                null, RESOURCE, "R1", List.of(), start, start, start,
                start.plusHours(1), BigDecimal.ONE, 1, "PLANNED", false, 0);
    }
}
