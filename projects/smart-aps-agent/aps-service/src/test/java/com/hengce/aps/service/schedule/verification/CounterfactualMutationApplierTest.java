package com.hengce.aps.service.schedule.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 最小改动应用器：解冻与日历延长。
 */
class CounterfactualMutationApplierTest {

    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    @Test
    void unfreezeRemovesOnlyRequestedTaskIds() {
        ScheduleProblem problem = problem(List.of(
                frozen(11L, 10), frozen(12L, 20)), List.of(resource(List.of())));

        CounterfactualMutationApplier.Mutation mutation =
                CounterfactualMutationApplier.unfreeze(problem, List.of(11L));

        assertThat(mutation.applicable()).isTrue();
        assertThat(mutation.affectedTaskCount()).isEqualTo(1);
        assertThat(mutation.problem().frozenAssignments()).hasSize(1);
        assertThat(mutation.problem().frozenAssignments().get(0).taskId()).isEqualTo(12L);
        assertThat(problem.frozenAssignments()).hasSize(2);
    }

    @Test
    void unfreezeWithoutMatchingFrozenTaskIsUnavailable() {
        CounterfactualMutationApplier.Mutation mutation = CounterfactualMutationApplier.unfreeze(
                problem(List.of(frozen(11L, 10)), List.of(resource(List.of()))), List.of(99L));

        assertThat(mutation.applicable()).isFalse();
        assertThat(mutation.reason()).contains("冻结分配");
        assertThat(mutation.affectedTaskCount()).isZero();
    }

    @Test
    void extendCalendarAddsWindowAndCountsAffectedOperations() {
        OffsetDateTime from = OffsetDateTime.of(2026, 11, 6, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(2026, 11, 7, 0, 0, 0, 0, ZoneOffset.UTC);
        ScheduleProblem problem = problem(List.of(),
                List.of(resource(List.of(new AvailabilityWindow(
                        OffsetDateTime.of(2026, 11, 5, 0, 0, 0, 0, ZoneOffset.UTC), from)))));

        CounterfactualMutationApplier.Mutation mutation =
                CounterfactualMutationApplier.extendCalendar(problem, RESOURCE, from, to);

        assertThat(mutation.applicable()).isTrue();
        assertThat(mutation.affectedTaskCount()).isEqualTo(2);
        assertThat(mutation.problem().resources().get(0).availabilityWindows()).hasSize(1);
        assertThat(mutation.problem().resources().get(0).availabilityWindows().get(0).end())
                .isEqualTo(to);
    }

    @Test
    void extendCalendarWithCoveredWindowIsUnavailable() {
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime end = OffsetDateTime.of(2026, 11, 7, 0, 0, 0, 0, ZoneOffset.UTC);
        ScheduleProblem problem = problem(List.of(),
                List.of(resource(List.of(new AvailabilityWindow(start, end)))));

        CounterfactualMutationApplier.Mutation mutation =
                CounterfactualMutationApplier.extendCalendar(problem, RESOURCE,
                        start.plusHours(1), end.minusHours(1));

        assertThat(mutation.applicable()).isFalse();
        assertThat(mutation.reason()).contains("没有为资源新增可用时间");
    }

    @Test
    void extendCalendarOnTwentyFourSevenResourceIsUnavailable() {
        ScheduleProblem problem = problem(List.of(), List.of(resource(List.of())));

        CounterfactualMutationApplier.Mutation mutation =
                CounterfactualMutationApplier.extendCalendar(problem, RESOURCE,
                        OffsetDateTime.of(2026, 11, 6, 0, 0, 0, 0, ZoneOffset.UTC),
                        OffsetDateTime.of(2026, 11, 7, 0, 0, 0, 0, ZoneOffset.UTC));

        assertThat(mutation.applicable()).isFalse();
        assertThat(mutation.reason()).contains("7×24");
    }

    private static ScheduleProblem problem(List<TaskAssignment> frozen,
                                           List<SchedulingResource> resources) {
        SchedulingOperation op10 = new SchedulingOperation(10, "OP10", "裁切", null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOperation op20 = new SchedulingOperation(20, "OP20", "组装", null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(ORDER, "MO-A", "P-A", BigDecimal.ONE,
                LocalDate.of(2026, 11, 10), "NORMAL", "FROZEN", null, List.of(op10, op20));
        return new ScheduleProblem(TENANT, ScheduleProblem.DEFAULT_SANDBOX_ID, null, null,
                "FORWARD", List.of(order), resources, frozen, List.of(), List.of(),
                LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }

    private static SchedulingResource resource(List<AvailabilityWindow> windows) {
        return new SchedulingResource(RESOURCE, "R1", "PRIMARY", null, "UNIT_PER_HOUR",
                BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE", windows);
    }

    private static TaskAssignment frozen(Long taskId, int operationIndex) {
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
        return new TaskAssignment(taskId, ORDER, "MO-A", operationIndex, "OP", null, RESOURCE,
                "R1", List.of(), start, start, start, start.plusHours(1), BigDecimal.ONE, 1,
                "FROZEN", true, 0);
    }
}
