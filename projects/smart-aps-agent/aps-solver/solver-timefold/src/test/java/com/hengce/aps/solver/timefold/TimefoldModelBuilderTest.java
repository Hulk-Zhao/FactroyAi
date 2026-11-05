package com.hengce.aps.solver.timefold;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型构建的降级标记与 C12 输入校验。
 */
class TimefoldModelBuilderTest {

    @Test
    void missingCalendarDependencyAndMatrixSetFallbackFlags() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-FB", List.of(
                SolverFixtures.operation(10, resourceId),
                SolverFixtures.operation(20, resourceId)));

        SchedulePlan plan = TimefoldModelBuilder.build(SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC")),
                List.of()));

        assertTrue(plan.isCalendarFallback());
        assertTrue(plan.isDependencyFallback());
        assertTrue(plan.isSetupMatrixFallback());
    }

    @Test
    void explicitCalendarDependenciesAndMatrixClearFallbackFlags() {
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusDays(8);
        SchedulingOrder order = SolverFixtures.order("SO-OK", List.of(
                SolverFixtures.operation(10, resourceId),
                SolverFixtures.operation(20, resourceId,
                        List.of(new OperationDependency(10, "FS", 0)))));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC",
                        List.of(new AvailabilityWindow(from, to)))),
                List.of(),
                List.of(new SetupMatrixEntry(null, "OPERATION_CODE", "OP-10", "OP-20", 600)));

        SchedulePlan plan = TimefoldModelBuilder.build(problem);

        assertFalse(plan.isCalendarFallback());
        assertFalse(plan.isDependencyFallback());
        assertFalse(plan.isSetupMatrixFallback());
        assertFalse(plan.getDependencies().isEmpty());
    }

    @Test
    void overlappingAvailabilityWindowsAreRejected() {
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        SchedulingOrder order = SolverFixtures.order("SO-WIN", List.of(
                SolverFixtures.operation(10, resourceId)));
        ScheduleProblem problem = SolverFixtures.problem(
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC", List.of(
                        new AvailabilityWindow(from, from.plusDays(2)),
                        new AvailabilityWindow(from.plusDays(1), from.plusDays(3))))),
                List.of());

        assertThrows(IllegalArgumentException.class, () -> TimefoldModelBuilder.build(problem));
    }

    @Test
    void startTimeCandidatesRequireWholeTaskInsideShift() {
        UUID resourceId = UUID.randomUUID();
        SchedulePlan plan = buildPlan(resourceId,
                List.of(operation(resourceId, 600, 10)),
                List.of(window(BASE.plusHours(8), BASE.plusHours(16))));

        // 2h10m 任务：13:00 起 15:10 结束（合法）；14:00 起 16:10 越界，必须从候选集裁掉。
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(8)));
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(13)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(14)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(15)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(16)));
    }

    @Test
    void startTimeCandidateAllowedWhenTaskExactlyFillsWindow() {
        UUID resourceId = UUID.randomUUID();
        // 换型 600s + 加工 2350s×12 = 28800s，正好 8 小时。
        SchedulePlan plan = buildPlan(resourceId,
                List.of(operation(resourceId, 2350, 10)),
                List.of(window(BASE.plusHours(8), BASE.plusHours(16))));

        assertTrue(plan.getStartTimes().contains(BASE.plusHours(8)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(9)));
    }

    @Test
    void crossDayWindowsClipEachShiftIndependently() {
        UUID resourceId = UUID.randomUUID();
        SchedulePlan plan = buildPlan(resourceId,
                List.of(operation(resourceId, 600, 10)),
                List.of(
                        window(BASE.plusHours(8), BASE.plusHours(16)),
                        window(BASE.plusHours(32), BASE.plusHours(40))));

        // 第一天：13:00 合法，14:00 越界。
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(13)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(14)));
        // 第二天独立判断：08:00/13:00 合法，14:00 越界；两班之间的间隔不产生候选。
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(32)));
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(37)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(38)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(31)));
    }

    @Test
    void edgeCandidateKeptWhenShorterTaskStillFits() {
        UUID resourceId = UUID.randomUUID();
        SchedulePlan plan = buildPlan(resourceId,
                List.of(operation(resourceId, 600, 10), operation(resourceId, 60, 20)),
                List.of(window(BASE.plusHours(8), BASE.plusHours(16))));

        // 15:00 对 2h10m 任务越界，但对 22min 任务仍合法（15:22 结束），不得误裁。
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(15)));
        assertFalse(plan.getStartTimes().contains(BASE.plusHours(16)));
    }

    @Test
    void frozenStartAfterShiftEndStaysFixedAndIsCountedOutsideCalendar() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-FRZ-AFTER", List.of(
                operation(resourceId, 600, 10)));
        // 班次 08:00-16:00，冻结起点 16:10（班次结束后 10 分钟），2h10m 任务必然越界。
        LocalDateTime pinnedStart = BASE.plusHours(16).plusMinutes(10);
        SchedulePlan plan = buildPlanWithFrozen(order, resourceId,
                List.of(window(BASE.plusHours(8), BASE.plusHours(16))),
                SolverFixtures.frozen(order, 10, resourceId, "R-CNC", pinnedStart, 7800, List.of()));

        TaskPlan task = plan.getTasks().get(0);
        assertEquals(pinnedStart, task.getStartTime(), "冻结起点必须原样保留，不得静默合法化");
        assertTrue(task.isPinned());
        // Timefold 初始化要求已赋值属于值域，非法冻结起点也必须保留在值域；可见性靠
        // metrics/日志/硬约束归因，而不是把它从值域里移除（移除会让求解直接 FAILED）。
        assertTrue(plan.getStartTimes().contains(pinnedStart),
                "已赋值必须属于值域，否则 Timefold 初始化直接失败");
        assertTrue(plan.getStartTimes().contains(BASE.plusHours(8)), "合法候选不能被误删");
        assertEquals(1, TimefoldModelBuilder.frozenStartOutsideCalendarCount(plan.getTasks()));
    }

    @Test
    void frozenStartBeforeWindowStaysFixedAndIsCountedOutsideCalendar() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-FRZ-BEFORE", List.of(
                operation(resourceId, 600, 10)));
        // 班次 08:00-16:00，冻结起点 07:30，占用块从窗口前开始。
        LocalDateTime pinnedStart = BASE.plusHours(7).plusMinutes(30);
        SchedulePlan plan = buildPlanWithFrozen(order, resourceId,
                List.of(window(BASE.plusHours(8), BASE.plusHours(16))),
                SolverFixtures.frozen(order, 10, resourceId, "R-CNC", pinnedStart, 7800, List.of()));

        TaskPlan task = plan.getTasks().get(0);
        assertEquals(pinnedStart, task.getStartTime());
        assertTrue(plan.getStartTimes().contains(pinnedStart));
        assertEquals(1, TimefoldModelBuilder.frozenStartOutsideCalendarCount(plan.getTasks()));
    }

    @Test
    void legalFrozenStartIsNotFlaggedOutsideCalendar() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = SolverFixtures.order("SO-FRZ-OK", List.of(
                operation(resourceId, 600, 10)));
        LocalDateTime pinnedStart = BASE.plusHours(9);
        SchedulePlan plan = buildPlanWithFrozen(order, resourceId,
                List.of(window(BASE.plusHours(8), BASE.plusHours(16))),
                SolverFixtures.frozen(order, 10, resourceId, "R-CNC", pinnedStart, 7800, List.of()));

        TaskPlan task = plan.getTasks().get(0);
        assertEquals(pinnedStart, task.getStartTime());
        assertTrue(plan.getStartTimes().contains(pinnedStart),
                "合法冻结起点必须保留在值域，避免误伤正常冻结计划");
        assertEquals(0, TimefoldModelBuilder.frozenStartOutsideCalendarCount(plan.getTasks()));
    }

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 5, 0, 0);

    private static SchedulePlan buildPlan(
            UUID resourceId, List<SchedulingOperation> operations,
            List<AvailabilityWindow> windows) {
        ScheduleProblem problem = new ScheduleProblem(
                "tenant-test",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                List.of(SolverFixtures.order("SO-CAL", operations)),
                List.of(SolverFixtures.resource(resourceId, "R-CNC", windows)),
                List.of(),
                List.of(),
                List.of(),
                BASE,
                List.of());
        return TimefoldModelBuilder.build(problem);
    }

    private static SchedulePlan buildPlanWithFrozen(
            SchedulingOrder order, UUID resourceId,
            List<AvailabilityWindow> windows, TaskAssignment frozen) {
        ScheduleProblem problem = new ScheduleProblem(
                "tenant-test",
                ScheduleProblem.DEFAULT_SANDBOX_ID,
                null,
                null,
                "FORWARD",
                List.of(order),
                List.of(SolverFixtures.resource(resourceId, "R-CNC", windows)),
                List.of(frozen),
                List.of(),
                List.of(),
                BASE,
                List.of());
        return TimefoldModelBuilder.build(problem);
    }

    private static SchedulingOperation operation(
            UUID resourceId, long standardSeconds, int operationIndex) {
        return new SchedulingOperation(
                operationIndex,
                "OP-" + operationIndex,
                "工序 " + operationIndex,
                null,
                BigDecimal.valueOf(standardSeconds),
                BigDecimal.valueOf(600),
                resourceId,
                null,
                List.of(),
                false,
                List.of());
    }

    private static AvailabilityWindow window(LocalDateTime start, LocalDateTime end) {
        return new AvailabilityWindow(start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC));
    }
}
