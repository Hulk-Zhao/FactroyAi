package com.hengce.aps.service.schedule.atp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * FAST 构造启发式：正常、资源满载、无可用资源三场景 + P95 门槛。
 */
class AtpFastEstimatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2026-11-05 00:00 +08:00。 */
    private static final OffsetDateTime READY = OffsetDateTime.parse("2026-11-04T16:00:00Z");

    private static final LocalDate REQUESTED = LocalDate.of(2026, 11, 6);

    private static final OffsetDateTime HORIZON_END = READY.plusDays(30);

    private static final int P95_SAMPLES = 50;

    @Test
    void normalScenarioReturnsEarliestDate() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = order(2);
        SchedulingResource resource = resource(resourceId);

        AtpFastEstimator.FastEstimate estimate = AtpFastEstimator.estimate(order, List.of(resource),
                Map.of(), READY, HORIZON_END, REQUESTED, false, ZONE);

        assertTrue(estimate.resourcesAvailable());
        assertTrue(estimate.completed());
        assertEquals(LocalDate.of(2026, 11, 5), estimate.earliestDeliveryDate());
        assertEquals(2, estimate.placements().size());
        assertEquals(List.of("R01"), estimate.resourceCodes());
        assertTrue(estimate.bottlenecks().stream().anyMatch(b -> "OPERATION_DURATION".equals(b.type())));
    }

    @Test
    void saturatedScenarioPushesDeliveryOut() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = order(2);
        SchedulingResource resource = resource(resourceId);
        AvailabilityWindow busyWindow = new AvailabilityWindow(READY, READY.plusDays(3));

        AtpFastEstimator.FastEstimate estimate = AtpFastEstimator.estimate(order, List.of(resource),
                Map.of(resourceId, List.of(busyWindow)), READY, HORIZON_END, LocalDate.of(2026, 11, 10),
                false, ZONE);

        assertTrue(estimate.completed());
        assertEquals(LocalDate.of(2026, 11, 8), estimate.earliestDeliveryDate());
        assertTrue(estimate.bottlenecks().stream().anyMatch(b -> "RESOURCE_QUEUE".equals(b.type())));
        assertTrue(estimate.suggestions().stream().anyMatch(s -> "ADD_OVERTIME".equals(s.action())));
    }

    @Test
    void noResourceScenarioReturnsNoDate() {
        AtpFastEstimator.FastEstimate estimate = AtpFastEstimator.estimate(order(2), List.of(),
                Map.of(), READY, HORIZON_END, REQUESTED, false, ZONE);

        assertFalse(estimate.resourcesAvailable());
        assertFalse(estimate.completed());
        assertEquals(null, estimate.earliestDeliveryDate());
        assertTrue(estimate.bottlenecks().stream().anyMatch(b -> "NO_RESOURCE".equals(b.type())));
        assertTrue(estimate.suggestions().stream().anyMatch(s -> "MAINTAIN_RESOURCE".equals(s.action())));
    }

    @Test
    void fastEstimatorP95UnderTwoSeconds() {
        UUID resourceId = UUID.randomUUID();
        SchedulingOrder order = order(2);
        SchedulingResource resource = resource(resourceId);
        long[] elapsedMillis = new long[P95_SAMPLES];
        for (int i = 0; i < P95_SAMPLES; i++) {
            long startedAt = System.nanoTime();
            AtpFastEstimator.FastEstimate estimate = AtpFastEstimator.estimate(order, List.of(resource),
                    Map.of(), READY, HORIZON_END, REQUESTED, false, ZONE);
            elapsedMillis[i] = (System.nanoTime() - startedAt) / 1_000_000L;
            assertNotNull(estimate.earliestDeliveryDate());
        }
        Arrays.sort(elapsedMillis);
        int index = (int) Math.ceil(P95_SAMPLES * 0.95D) - 1;
        long p95 = elapsedMillis[Math.max(0, index)];
        assertTrue(p95 < 2_000L, "FAST P95 必须 < 2s，实际 " + p95 + "ms");
    }

    private static SchedulingOrder order(int qty) {
        List<SchedulingOperation> operations = List.of(
                operation(1, "OP10", "切割"),
                operation(2, "OP20", "装配"));
        return new SchedulingOrder(UUID.randomUUID(), "ATP-T1", "MAT-1", null,
                BigDecimal.valueOf(qty), REQUESTED, "NORMAL", "UNFROZEN", UUID.randomUUID(), operations);
    }

    private static SchedulingOperation operation(int index, String code, String name) {
        return new SchedulingOperation(index, code, name, null, BigDecimal.ONE, BigDecimal.ZERO,
                null, null, List.of(), false);
    }

    private static SchedulingResource resource(UUID resourceId) {
        return new SchedulingResource(resourceId, "R01", "PRIMARY", null, "UNIT_PER_HOUR",
                BigDecimal.valueOf(10), 1, BigDecimal.ONE, null, "ACTIVE", List.of());
    }
}
