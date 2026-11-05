package com.hengce.aps.service.schedule.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository.SolveTaskReplayRow;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AssembledProblem;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalyzer;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.counterfactual.CounterfactualKind;
import com.hengce.aps.solver.api.counterfactual.CounterfactualRequest;
import com.hengce.aps.solver.api.counterfactual.CounterfactualResult;
import com.hengce.aps.solver.api.counterfactual.CounterfactualStatus;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * C52 真实样本：冻结重叠场景在 Timefold 上从不可行变为可行。
 *
 * <p>场景：单资源可用 08:00-11:00（3h）。A 单 1h 被冻结在 09:00-10:00，把日历切成
 * 两个 1h 空洞；B 单需要连续 2h，无法放入任何空洞 → 不可行。解冻 A 后 A+B 共 3h
 * 可顺序排入 → 可行。样本里的分数与排布全部来自真实 Timefold 求解。</p>
 */
class VerificationSolverTimefoldSampleTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ORDER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID ORDER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final OffsetDateTime WINDOW_START =
            OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime WINDOW_END =
            OffsetDateTime.of(2026, 11, 5, 11, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void frozenOverlapBecomesFeasibleAfterUnfreeze() {
        VerificationSolver solver = timefoldSolver();
        ScheduleProblem problem = problem();

        VerificationOutcome before = solver.solve(problem, config());
        CounterfactualMutationApplier.Mutation mutation =
                CounterfactualMutationApplier.unfreeze(problem, List.of(11L));
        VerificationOutcome after = solver.solve(mutation.problem(), config());

        assertThat(before.status()).isEqualTo(VerificationStatus.INFEASIBLE);
        assertThat(before.hardScore()).isNotNull();
        assertThat(before.hardScore().signum()).isLessThan(0);
        assertThat(after.status()).isEqualTo(VerificationStatus.FEASIBLE);
        assertThat(after.hardScore()).isNotNull();
        assertThat(after.hardScore().signum()).isZero();
        assertThat(after.assignedTaskCount()).isEqualTo(2);
        assertThat(after.unassignedTaskCount()).isZero();
        assertThat(after.assignments()).hasSize(2);
        assertThat(after.mainPlanWritten()).isFalse();
        assertThat(after.persisted()).isFalse();
        after.assignments().forEach(assignment -> {
            assertThat(assignment.startTime()).isNotNull();
            assertThat(assignment.endTime()).isNotNull();
            assertThat(assignment.resourceCode()).isEqualTo("R1");
            assertThat(assignment.startTime()).isAfterOrEqualTo(WINDOW_START);
            assertThat(assignment.endTime()).isBeforeOrEqualTo(WINDOW_END);
        });
        System.out.println("[C52-SAMPLE] before=" + before.status() + " hard="
                + before.hardScore() + " unassigned=" + before.unassignedTaskCount()
                + " | after=" + after.status() + " hard=" + after.hardScore() + " soft="
                + after.softScore() + " assigned=" + after.assignedTaskCount() + "/"
                + after.totalTaskCount() + " assignments=" + after.assignments());
    }

    /**
     * 端点服务层直通样本：真实 Timefold 重解 + C52 编排 + changes 输出。
     *
     * <p>只 mock 数据读取（solve_task / 题面装配 / 归因报告 / 既有任务），
     * 求解本身与状态映射全部走真实实现。</p>
     */
    @Test
    void unfreezeThroughCounterfactualServiceIsVerifiedFeasible() {
        TenantContext.setTenantId(TENANT);
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        InfeasibilityQueryRepository queryRepository = mock(InfeasibilityQueryRepository.class);
        InfeasibilityAnalyzer analyzer = mock(InfeasibilityAnalyzer.class);
        SolveTaskRepository solveTaskRepository = mock(SolveTaskRepository.class);
        ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
        when(solveTaskRepository.findById(any(), anyLong()))
                .thenReturn(Optional.of(mock(SolveTaskRepository.SolveTaskRecord.class)));
        when(queryRepository.findReplayInput(any(), anyLong())).thenReturn(Optional.of(
                new SolveTaskReplayRow(ScheduleProblem.DEFAULT_SANDBOX_ID, null, "timefold",
                        "FAILED", replayConfigJson())));
        when(assembler.assemble(any(), any())).thenReturn(new AssembledProblem(TENANT,
                ScheduleProblem.DEFAULT_SANDBOX_ID, problem(), List.of(ORDER_A, ORDER_B)));
        CounterfactualService service = new CounterfactualService(assembler, queryRepository,
                analyzer, solveTaskRepository, scheduleRepository, timefoldSolver());

        CounterfactualResult result = service.verify(7L,
                new CounterfactualRequest(CounterfactualKind.UNFREEZE_TASKS,
                        Map.of("taskIds", List.of(11L)), 5));

        assertThat(result.status()).isEqualTo(CounterfactualStatus.VERIFIED_FEASIBLE);
        assertThat(result.feasible()).isTrue();
        assertThat(result.mainPlanWritten()).isFalse();
        assertThat(result.changes()).isNotEmpty();
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).taskId()).isEqualTo(11L);
        assertThat(result.changes().get(0).orderNo()).isEqualTo("MO-A");
        assertThat(result.changes().get(0).resourceCode()).isEqualTo("R1");
        assertThat(result.solver()).isNotNull();
        assertThat(result.solver().hardScore()).isNotNull();
        System.out.println("[C52-ENDPOINT-SAMPLE] status=" + result.status() + " feasible="
                + result.feasible() + " affected=" + result.affectedTaskCount() + " changes="
                + result.changes() + " solver=" + result.solver() + " mainPlanWritten="
                + result.mainPlanWritten());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static String replayConfigJson() {
        return """
                {"engine":"timefold","timeoutSeconds":60,"moveThreadCount":0,
                 "acceptor":"LATE_ACCEPTANCE","randomSeed":0,"maximumStepCount":0,
                 "unimprovedSecondsSpentLimit":10,"direction":"FORWARD",
                 "useFrozenAssignments":true,"orderNos":["MO-A","MO-B"],"parameters":{}}
                """;
    }

    private static VerificationSolver timefoldSolver() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SolverAdapter> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation ->
                Stream.of(new TimefoldSolverAdapter()));
        return new VerificationSolver(provider);
    }

    private static SolveConfig config() {
        return new SolveConfig("timefold", 5, 0, "LATE_ACCEPTANCE", 0L, 0L, 2, "FORWARD", true,
                Map.of());
    }

    private static ScheduleProblem problem() {
        SchedulingOperation opA = new SchedulingOperation(10, "OP10", "裁切", null,
                BigDecimal.valueOf(3600), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOperation opB = new SchedulingOperation(20, "OP20", "组装", null,
                BigDecimal.valueOf(7200), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOrder orderA = new SchedulingOrder(ORDER_A, "MO-A", "P-A", BigDecimal.ONE,
                LocalDate.of(2026, 11, 6), "NORMAL", "UNFROZEN", null, List.of(opA));
        SchedulingOrder orderB = new SchedulingOrder(ORDER_B, "MO-B", "P-B", BigDecimal.ONE,
                LocalDate.of(2026, 11, 6), "NORMAL", "UNFROZEN", null, List.of(opB));
        SchedulingResource resource = new SchedulingResource(RESOURCE, "R1", "PRIMARY", null,
                "SECONDS_PER_UNIT", BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE",
                List.of(new AvailabilityWindow(WINDOW_START, WINDOW_END)));
        OffsetDateTime frozenStart = OffsetDateTime.of(2026, 11, 5, 9, 0, 0, 0, ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(11L, ORDER_A, "MO-A", 10, "OP10", null,
                RESOURCE, "R1", List.of(), frozenStart, frozenStart, frozenStart,
                frozenStart.plusHours(1), BigDecimal.ONE, 1, "FROZEN", true, 0);
        return new ScheduleProblem(TENANT.toString(), ScheduleProblem.DEFAULT_SANDBOX_ID, null,
                null, "FORWARD", List.of(orderA, orderB), List.of(resource), List.of(frozen),
                List.of(), List.of(), LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }
}
