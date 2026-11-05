package com.hengce.aps.service.schedule.ctp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategoryMapper;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpSnapshot;
import com.hengce.aps.service.schedule.verification.VerificationSolver;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.ctp.CtpVerdict;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * C53 容量验证真实样本：可承诺订单走通 PROMISED；物料下界晚于交期时不求解。
 *
 * <p>装配器以 mock 提供题面，求解使用真实 Timefold。</p>
 */
class CtpCapacityVerifierTimefoldSampleTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 5, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime WINDOW_START =
            OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime WINDOW_END =
            OffsetDateTime.of(2026, 11, 12, 8, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void orderThatFitsIsPromisedWithRealSolverPlan() {
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        SchedulingOrder atpOrder = atpOrder();
        ScheduleProblem problem = problem(atpOrder);
        when(assembler.assembleAtp(any(), anyInt(), anyBoolean())).thenReturn(new AtpSnapshot(
                TENANT, ScheduleProblem.DEFAULT_SANDBOX_ID, problem, atpOrder,
                WINDOW_START, WINDOW_END, 0));
        CtpCapacityVerifier verifier = verifier(assembler);

        CtpCapacityVerifier.CapacityOutcome outcome = verifier.verify(material(),
                normalized(BigDecimal.valueOf(2), LocalDate.of(2026, 11, 6), 3),
                LocalDate.of(2026, 11, 5), "NORMAL", NOW);

        assertThat(outcome.verdict()).isEqualTo(CtpVerdict.PROMISED);
        assertThat(outcome.promisedDate()).isNotNull();
        assertThat(outcome.promisedDate()).isBeforeOrEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(outcome.promisedQty()).isEqualByComparingTo("2");
        assertThat(outcome.capacityVerifiedAt()).isNotNull();
        assertThat(outcome.capacityVerifiedAt()).isAfterOrEqualTo(NOW);
        assertThat(outcome.solver()).isNotNull();
        assertThat(outcome.solver().hardScore()).isEqualByComparingTo("0");
        assertThat(outcome.solver().solveTaskId()).isNull();
        assertThat(outcome.evidence()).containsEntry("kind", "PROMISED");
        System.out.println("[C53-SAMPLE] verdict=" + outcome.verdict() + " promisedDate="
                + outcome.promisedDate() + " promisedQty=" + outcome.promisedQty()
                + " hard=" + outcome.solver().hardScore() + " soft="
                + outcome.solver().softScore() + " evidence=" + outcome.evidence());
    }

    @Test
    void materialReadyAfterRequestedDateIsUnpromisableWithoutSolving() {
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        CtpCapacityVerifier verifier = verifier(assembler);

        CtpCapacityVerifier.CapacityOutcome outcome = verifier.verify(material(),
                normalized(BigDecimal.ONE, LocalDate.of(2026, 11, 6), 3),
                LocalDate.of(2026, 11, 9), "NORMAL", NOW);

        assertThat(outcome.verdict()).isEqualTo(CtpVerdict.UNPROMISABLE);
        assertThat(outcome.promisedDate()).isNull();
        assertThat(outcome.solver()).isNull();
        verify(assembler, never()).assembleAtp(any(), anyInt(), anyBoolean());
    }

    private static CtpCapacityVerifier verifier(ScheduleProblemAssembler assembler) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SolverAdapter> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation ->
                Stream.of(new TimefoldSolverAdapter()));
        VerificationSolver solver = new VerificationSolver(provider);
        return new CtpCapacityVerifier(assembler, solver, mock(MaterialCategoryMapper.class),
                "Asia/Shanghai", 168);
    }

    private static Material material() {
        Material material = new Material();
        material.setCode("FG-1");
        return material;
    }

    private static CtpRequestValidator.NormalizedCtpRequest normalized(BigDecimal qty,
                                                                       LocalDate requestedDate,
                                                                       int timeoutSeconds) {
        return new CtpRequestValidator.NormalizedCtpRequest("FG-1", qty, requestedDate, null,
                false, timeoutSeconds);
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
                List.of(new AvailabilityWindow(WINDOW_START, WINDOW_END)));
        return new ScheduleProblem(TENANT.toString(), ScheduleProblem.DEFAULT_SANDBOX_ID, null,
                null, "FORWARD", List.of(atpOrder), List.of(resource), List.of(), List.of(),
                List.of(), LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }
}
