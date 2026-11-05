package com.hengce.aps.service.schedule.atp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategoryMapper;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpOrderSpec;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpSnapshot;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolverAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 服务层边界：订单不存在 404/40400、非法状态 422/42200、无物料 404/40400，以及 FAST 只读语义。
 */
@SuppressWarnings("unchecked")
class AtpServiceBoundaryTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final LocalDate REQUESTED = LocalDate.of(2026, 11, 6);

    private static final OffsetDateTime READY = OffsetDateTime.parse("2026-11-04T16:00:00Z");

    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    private final ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
    private final MaterialMapper materialMapper = mock(MaterialMapper.class);
    private final MaterialCategoryMapper materialCategoryMapper = mock(MaterialCategoryMapper.class);
    private final ObjectProvider<SolverAdapter> solverAdapters = mock(ObjectProvider.class);
    private final AtpService service = new AtpService(scheduleRepository, assembler, materialMapper,
            materialCategoryMapper, solverAdapters, "Asia/Shanghai", 10, 1080, 172);

    @Test
    void unknownOrderReturnsNotFound() {
        when(scheduleRepository.findOrders(any(), anyList(), eq(false))).thenReturn(List.of());
        AtpQuery query = new AtpQuery("MAT-1", BigDecimal.TEN, REQUESTED, null,
                "NO-SUCH-ORDER", "FAST", null, null);

        BizException exception = assertThrows(BizException.class, () -> service.estimate(query));
        assertEquals(40400, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("NO-SUCH-ORDER"));
    }

    @Test
    void cancelledOrderIsUnprocessable() {
        OrderSourceRow row = new OrderSourceRow(UUID.randomUUID(), "SO-1", null, "MAT-1", null,
                BigDecimal.TEN, REQUESTED, "NORMAL", "UNFROZEN", null, "CANCELLED");
        when(scheduleRepository.findOrders(any(), anyList(), eq(false))).thenReturn(List.of(row));
        AtpQuery query = new AtpQuery("MAT-1", BigDecimal.TEN, REQUESTED, null,
                "SO-1", "FAST", null, null);

        BizException exception = assertThrows(BizException.class, () -> service.estimate(query));
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void unknownMaterialReturnsNotFound() {
        when(materialMapper.selectList(any())).thenReturn(List.of());
        AtpQuery query = new AtpQuery("MAT-X", BigDecimal.TEN, REQUESTED, null,
                null, "FAST", null, null);

        BizException exception = assertThrows(BizException.class, () -> service.estimate(query));
        assertEquals(40400, exception.getErrorCode().getCode());
    }

    @Test
    void fastResultIsEstimateAndNeverWritesMainPlan() {
        Material material = new Material();
        material.setCode("MAT-1");
        when(materialMapper.selectList(any())).thenReturn(List.of(material));
        when(scheduleRepository.findGanttTasks(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        UUID resourceId = UUID.randomUUID();
        SchedulingResource resource = new SchedulingResource(resourceId, "R01", "PRIMARY", null,
                "UNIT_PER_HOUR", BigDecimal.valueOf(10), 1, BigDecimal.ONE, null, "ACTIVE", List.of());
        List<SchedulingOperation> operations = List.of(
                new SchedulingOperation(1, "OP10", "切割", null, BigDecimal.ONE, BigDecimal.ZERO,
                        resourceId, null, List.of(), false),
                new SchedulingOperation(2, "OP20", "装配", null, BigDecimal.ONE, BigDecimal.ZERO,
                        resourceId, null, List.of(), false));
        UUID orderId = UUID.randomUUID();
        SchedulingOrder atpOrder = new SchedulingOrder(orderId, "ATP-T1", "MAT-1", null,
                BigDecimal.valueOf(2), REQUESTED, "NORMAL", "UNFROZEN", UUID.randomUUID(), operations);
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(),
                ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                List.of(atpOrder), List.of(resource), List.of(), List.of(), List.of());
        AtpSnapshot snapshot = new AtpSnapshot(TENANT_ID,
                ScheduleProblem.DEFAULT_SANDBOX_ID, problem, atpOrder, READY, READY.plusDays(30), 0);
        when(assembler.assembleAtp(any(AtpOrderSpec.class), anyInt(), anyBoolean()))
                .thenReturn(snapshot);

        AtpQuery query = new AtpQuery("MAT-1", BigDecimal.valueOf(2), REQUESTED, null,
                null, "FAST", null, null);
        AtpResult result = service.estimate(query);

        assertEquals(AtpMode.FAST, result.mode());
        assertEquals(AtpAccuracy.ESTIMATE, result.accuracy());
        assertEquals(AtpStatus.OK, result.status());
        assertEquals(LocalDate.of(2026, 11, 5), result.earliestDeliveryDate());
        assertEquals(result.earliestDeliveryDate(), result.earliestDate());
        assertEquals(false, result.mainPlanWritten());
        assertNotNull(result.capacityLockSuggestion());
        assertTrue(result.capacityLockSuggestion().advisoryOnly());
        assertTrue(result.capacityLockSuggestion().required());
        assertEquals(List.of("R01"), result.capacityLockSuggestion().resourceCodes());
    }
}
