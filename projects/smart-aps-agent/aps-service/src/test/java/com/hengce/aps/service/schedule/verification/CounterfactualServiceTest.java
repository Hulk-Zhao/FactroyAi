package com.hengce.aps.service.schedule.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository.SolveTaskReplayRow;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AssembledProblem;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalysis;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalyzer;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.counterfactual.CounterfactualKind;
import com.hengce.aps.solver.api.counterfactual.CounterfactualRequest;
import com.hengce.aps.solver.api.counterfactual.CounterfactualResult;
import com.hengce.aps.solver.api.counterfactual.CounterfactualStatus;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C52 端点编排：形状、404/422、UNVERIFIED 与三种结论映射。
 */
class CounterfactualServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SANDBOX = ScheduleProblem.DEFAULT_SANDBOX_ID;

    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private ScheduleProblemAssembler assembler;

    private InfeasibilityQueryRepository queryRepository;

    private InfeasibilityAnalyzer analyzer;

    private SolveTaskRepository solveTaskRepository;

    private ScheduleRepository scheduleRepository;

    private VerificationSolver verificationSolver;

    private CounterfactualService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        assembler = mock(ScheduleProblemAssembler.class);
        queryRepository = mock(InfeasibilityQueryRepository.class);
        analyzer = mock(InfeasibilityAnalyzer.class);
        solveTaskRepository = mock(SolveTaskRepository.class);
        scheduleRepository = mock(ScheduleRepository.class);
        verificationSolver = mock(VerificationSolver.class);
        service = new CounterfactualService(assembler, queryRepository, analyzer,
                solveTaskRepository, scheduleRepository, verificationSolver);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void unknownSolveTaskIsNotFound() {
        when(solveTaskRepository.findById(any(), anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(1L,
                new CounterfactualRequest(CounterfactualKind.UNFREEZE_TASKS,
                        Map.of("taskIds", List.of(1)), 5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void missingKindIsUnprocessable() {
        assertThatThrownBy(() -> service.verify(1L,
                new CounterfactualRequest(null, Map.of(), 5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void unsupportedKindIsUnverifiedWithoutSolving() {
        replayProblem();
        CounterfactualResult result = service.verify(7L, new CounterfactualRequest(
                CounterfactualKind.ADD_RESOURCE, Map.of("resourceCode", "R-NEW"), 5));

        assertThat(result.status()).isEqualTo(CounterfactualStatus.UNVERIFIED);
        assertThat(result.mainPlanWritten()).isFalse();
        assertThat(result.solver()).isNull();
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void failingKindStillRequiresItsKeys() {
        replayProblem();
        assertThatThrownBy(() -> service.verify(7L, new CounterfactualRequest(
                CounterfactualKind.RELAX_DUE_DATE, Map.of("orderNos", List.of("MO-A")), 5)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void unfreezeWithRealSolverOutcomeIsVerifiedFeasible() {
        replayProblem();
        when(verificationSolver.solve(any(), any())).thenReturn(outcome(
                VerificationStatus.FEASIBLE, List.of(assignment(10)), 0));
        CounterfactualResult result = service.verify(7L, new CounterfactualRequest(
                CounterfactualKind.UNFREEZE_TASKS, Map.of("taskIds", List.of(11)), 5));

        assertThat(result.status()).isEqualTo(CounterfactualStatus.VERIFIED_FEASIBLE);
        assertThat(result.feasible()).isTrue();
        assertThat(result.mainPlanWritten()).isFalse();
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).taskId()).isEqualTo(11L);
        assertThat(result.affectedTaskCount()).isEqualTo(1);
        assertThat(result.unassignedTaskCount()).isZero();
    }

    @Test
    void unfreezeWithoutFrozenMatchIsUnverified() {
        replayProblem();
        CounterfactualResult result = service.verify(7L, new CounterfactualRequest(
                CounterfactualKind.UNFREEZE_TASKS, Map.of("taskIds", List.of(99)), 5));

        assertThat(result.status()).isEqualTo(CounterfactualStatus.UNVERIFIED);
        assertThat(result.solver()).isNull();
    }

    @Test
    void budgetExhaustionIsOutOfBudgetAndKeepsSolverSummary() {
        replayProblem();
        when(verificationSolver.solve(any(), any())).thenReturn(outcome(
                VerificationStatus.OUT_OF_BUDGET, List.of(), null));
        CounterfactualResult result = service.verify(7L, new CounterfactualRequest(
                CounterfactualKind.UNFREEZE_TASKS, Map.of("taskIds", List.of(11)), 1));

        assertThat(result.status()).isEqualTo(CounterfactualStatus.OUT_OF_BUDGET);
        assertThat(result.feasible()).isFalse();
        assertThat(result.unassignedTaskCount()).isNull();
        assertThat(result.solver()).isNotNull();
        assertThat(result.mainPlanWritten()).isFalse();
    }

    private void replayProblem() {
        when(solveTaskRepository.findById(any(), anyLong()))
                .thenReturn(Optional.of(mock(SolveTaskRepository.SolveTaskRecord.class)));
        when(analyzer.analyzeTask(any(), anyLong())).thenReturn(analysis());
        when(queryRepository.findReplayInput(any(), anyLong())).thenReturn(Optional.of(
                new SolveTaskReplayRow(SANDBOX, null, "timefold", "FAILED", configJson())));
        when(assembler.assemble(any(), any())).thenReturn(
                new AssembledProblem(TENANT, SANDBOX, problem(), List.of(ORDER)));
    }

    private static InfeasibilityAnalysis analysis() {
        return new InfeasibilityAnalysis(new InfeasibilityReport(7L,
                InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND, List.of(), List.of(),
                List.of(), List.of(), List.of("B1: 原始题面不可行"), null, null, 0L, false,
                null, InfeasibilityReport.LlmStatus.DISABLED), List.of());
    }

    private static String configJson() {
        return """
                {"engine":"timefold","timeoutSeconds":60,"moveThreadCount":0,"acceptor":"LATE_ACCEPTANCE",
                 "randomSeed":0,"maximumStepCount":0,"unimprovedSecondsSpentLimit":10,
                 "direction":"FORWARD","useFrozenAssignments":true,"orderNos":["MO-A"],
                 "parameters":{}}
                """;
    }

    private static ScheduleProblem problem() {
        SchedulingOperation op10 = new SchedulingOperation(10, "OP10", "裁切", null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOperation op20 = new SchedulingOperation(20, "OP20", "组装", null,
                BigDecimal.valueOf(60), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        SchedulingOrder order = new SchedulingOrder(ORDER, "MO-A", "P-A", BigDecimal.ONE,
                LocalDate.of(2026, 11, 10), "NORMAL", "UNFROZEN", null, List.of(op10, op20));
        SchedulingResource resource = new SchedulingResource(RESOURCE, "R1", "PRIMARY", null,
                "UNIT_PER_HOUR", BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE");
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
        TaskAssignment frozen = new TaskAssignment(11L, ORDER, "MO-A", 10, "OP10", null,
                RESOURCE, "R1", List.of(), start, start, start, start.plusHours(1),
                BigDecimal.ONE, 1, "FROZEN", true, 0);
        return new ScheduleProblem(TENANT.toString(), SANDBOX, null, null, "FORWARD",
                List.of(order), List.of(resource), List.of(frozen), List.of(), List.of(),
                LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }

    private static VerificationOutcome outcome(VerificationStatus status,
                                               List<TaskAssignment> assignments,
                                               Integer unassigned) {
        return new VerificationOutcome(status, "timefold", 0L, BigDecimal.ZERO, BigDecimal.ZERO,
                120L, "TERMINATED", 2, assignments.size(), unassigned, assignments, List.of(),
                false, false, "stub");
    }

    private static TaskAssignment assignment(int operationIndex) {
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
        return new TaskAssignment(null, ORDER, "MO-A", operationIndex, "OP", null, RESOURCE,
                "R1", List.of(), start, start, start, start.plusHours(1), BigDecimal.ONE, 1,
                "PLANNED", false, 0);
    }
}
