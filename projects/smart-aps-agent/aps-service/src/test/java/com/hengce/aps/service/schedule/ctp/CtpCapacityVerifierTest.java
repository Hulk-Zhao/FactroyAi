package com.hengce.aps.service.schedule.ctp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategoryMapper;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpSnapshot;
import com.hengce.aps.service.schedule.verification.VerificationOutcome;
import com.hengce.aps.service.schedule.verification.VerificationSolver;
import com.hengce.aps.service.schedule.verification.VerificationStatus;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.ctp.CtpVerdict;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * C53 严格接受条件：hard!=0 / 有未分配 / 完成晚于交期 / 超预算 / 无路由，一律不承诺。
 */
class CtpCapacityVerifierTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 5, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime WINDOW_START =
            OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime WINDOW_END =
            OffsetDateTime.of(2026, 11, 12, 8, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void infeasibleCapacityIsNeverPromised() {
        CtpCapacityVerifier verifier = verifier(outcome(VerificationStatus.INFEASIBLE,
                List.of(), -1));

        CtpCapacityVerifier.CapacityOutcome outcome = verify(verifier);

        assertThat(outcome.verdict()).isEqualTo(CtpVerdict.UNPROMISABLE);
        assertThat(outcome.promisedDate()).isNull();
        assertThat(outcome.promisedQty()).isNull();
        assertThat(outcome.capacityVerifiedAt()).isNull();
    }

    @Test
    void budgetExhaustionIsSolverTimeoutWithNoPromise() {
        CtpCapacityVerifier verifier = verifier(outcome(VerificationStatus.OUT_OF_BUDGET,
                List.of(), null));

        CtpCapacityVerifier.CapacityOutcome outcome = verify(verifier);

        assertThat(outcome.verdict()).isEqualTo(CtpVerdict.SOLVER_TIMEOUT);
        assertThat(outcome.promisedDate()).isNull();
        assertThat(outcome.promisedQty()).isNull();
    }

    @Test
    void completionAfterRequestedDateIsUnpromisable() {
        TaskAssignment late = assignment(OffsetDateTime.of(2026, 11, 7, 12, 0, 0, 0,
                ZoneOffset.UTC));
        CtpCapacityVerifier verifier = verifier(outcome(VerificationStatus.FEASIBLE,
                List.of(late), 0));

        CtpCapacityVerifier.CapacityOutcome outcome = verify(verifier);

        assertThat(outcome.verdict()).isEqualTo(CtpVerdict.UNPROMISABLE);
        assertThat(outcome.promisedDate()).isNull();
        assertThat(outcome.message()).contains("晚于请求交期");
    }

    @Test
    void missingRoutingIsNoRoute() {
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        when(assembler.assembleAtp(any(), anyInt(), anyBoolean()))
                .thenThrow(new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "物料没有 ACTIVE 工艺路线"));
        CtpCapacityVerifier verifier = verifier(assembler, mock(VerificationSolver.class));

        CtpCapacityVerifier.CapacityOutcome outcome = verify(verifier);

        assertThat(outcome.verdict()).isEqualTo(CtpVerdict.NO_ROUTE);
        assertThat(outcome.promisedDate()).isNull();
        assertThat(outcome.solver()).isNull();
    }

    private static CtpCapacityVerifier.CapacityOutcome verify(CtpCapacityVerifier verifier) {
        return verifier.verify(material(), normalized(), LocalDate.of(2026, 11, 5), "NORMAL",
                NOW);
    }

    private static CtpCapacityVerifier verifier(VerificationOutcome outcome) {
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        SchedulingOrder atpOrder = atpOrder();
        when(assembler.assembleAtp(any(), anyInt(), anyBoolean())).thenReturn(
                new AtpSnapshot(TENANT, ScheduleProblem.DEFAULT_SANDBOX_ID, problem(atpOrder),
                        atpOrder, WINDOW_START, WINDOW_END, 0));
        VerificationSolver solver = mock(VerificationSolver.class);
        when(solver.solve(any(), any(), any())).thenReturn(outcome);
        return verifier(assembler, solver);
    }

    private static CtpCapacityVerifier verifier(ScheduleProblemAssembler assembler,
                                                VerificationSolver solver) {
        return new CtpCapacityVerifier(assembler, solver, mock(MaterialCategoryMapper.class),
                "Asia/Shanghai", 168);
    }

    private static VerificationOutcome outcome(VerificationStatus status,
                                               List<TaskAssignment> assignments,
                                               Integer unassigned) {
        return new VerificationOutcome(status, "timefold", 0L, BigDecimal.ZERO, BigDecimal.ZERO,
                100L, "TERMINATED", 1, assignments.size(), unassigned, assignments, List.of(),
                false, false, "stub");
    }

    private static TaskAssignment assignment(OffsetDateTime end) {
        return new TaskAssignment(null, ORDER, "CTP-A1", 10, "OP10", null, RESOURCE, "R1",
                List.of(), end.minusHours(2), end.minusHours(2), end.minusHours(2), end,
                BigDecimal.ONE, 1, "PLANNED", false, 0);
    }

    private static Material material() {
        Material material = new Material();
        material.setCode("FG-1");
        return material;
    }

    private static CtpRequestValidator.NormalizedCtpRequest normalized() {
        return new CtpRequestValidator.NormalizedCtpRequest("FG-1", BigDecimal.valueOf(2),
                LocalDate.of(2026, 11, 6), null, false, 3);
    }

    private static SchedulingOrder atpOrder() {
        SchedulingOperation operation = new SchedulingOperation(10, "OP10", "组装", null,
                BigDecimal.valueOf(7200), BigDecimal.ZERO, RESOURCE, null, List.of(), false);
        return new SchedulingOrder(ORDER, "CTP-A1", "FG-1", BigDecimal.valueOf(2),
                LocalDate.of(2026, 11, 6), "NORMAL", "UNFROZEN", null, List.of(operation));
    }

    private static ScheduleProblem problem(SchedulingOrder atpOrder) {
        SchedulingResource resource = new SchedulingResource(RESOURCE, "R1", "PRIMARY", null,
                "SECONDS_PER_UNIT", BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE",
                List.of(new com.hengce.aps.solver.api.AvailabilityWindow(WINDOW_START, WINDOW_END)));
        return new ScheduleProblem(TENANT.toString(), ScheduleProblem.DEFAULT_SANDBOX_ID, null,
                null, "FORWARD", List.of(atpOrder), List.of(resource), List.of(), List.of(),
                List.of(), LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }
}
