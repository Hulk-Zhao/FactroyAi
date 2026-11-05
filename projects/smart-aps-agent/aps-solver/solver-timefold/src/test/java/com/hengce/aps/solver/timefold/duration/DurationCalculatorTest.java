package com.hengce.aps.solver.timefold.duration;

import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingResource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DurationCalculator} 的三种产能模式与边界测试。
 */
class DurationCalculatorTest {

    @Test
    void unitPerHourUsesHourlyCapacity() {
        SchedulingResource resource = resource(
                "UNIT_PER_HOUR", BigDecimal.TEN, null, BigDecimal.ONE);
        SchedulingOperation operation = operation(null, null);

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation, BigDecimal.valueOf(120));

        assertEquals(0L, duration.setupSeconds());
        assertEquals(43200L, duration.runSeconds());
        assertEquals(43200L, duration.totalSeconds());
    }

    @Test
    void unitPerHourAppliesEfficiency() {
        SchedulingResource resource = resource(
                "UNIT_PER_HOUR", BigDecimal.TEN, null, BigDecimal.valueOf(0.5));

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(null, null), BigDecimal.TEN);

        assertEquals(7200L, duration.runSeconds());
    }

    @Test
    void secondsPerUnitUsesStandardSecondsAndEfficiency() {
        SchedulingResource resource = resource(
                "SECONDS_PER_UNIT", BigDecimal.ONE, null, BigDecimal.valueOf(0.5));

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(BigDecimal.valueOf(60), BigDecimal.valueOf(300)),
                BigDecimal.TEN);

        assertEquals(300L, duration.setupSeconds());
        assertEquals(1200L, duration.runSeconds());
        assertEquals(1500L, duration.totalSeconds());
    }

    @Test
    void batchSecondsRoundsBatchCountUp() {
        SchedulingResource resource = resource(
                "BATCH_SECONDS", BigDecimal.valueOf(600), 10, BigDecimal.ONE);

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(null, BigDecimal.valueOf(300)), BigDecimal.valueOf(25));

        assertEquals(300L, duration.setupSeconds());
        assertEquals(1800L, duration.runSeconds());
    }

    @Test
    void batchCapacityDefaultsToOneWhenNull() {
        SchedulingResource resource = resource(
                "BATCH_SECONDS", BigDecimal.valueOf(60), null, BigDecimal.ONE);

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(null, null), BigDecimal.valueOf(5));

        assertEquals(300L, duration.runSeconds());
    }

    @Test
    void runSecondsRoundsUpToWholeSecond() {
        SchedulingResource resource = resource(
                "UNIT_PER_HOUR", BigDecimal.valueOf(7), null, BigDecimal.ONE);

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(null, null), BigDecimal.ONE);

        assertEquals(515L, duration.runSeconds());
    }

    @Test
    void nullEfficiencyAndSetupUseDefaults() {
        SchedulingResource resource = resource(
                "SECONDS_PER_UNIT", BigDecimal.ONE, null, null);

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(BigDecimal.valueOf(100), null), BigDecimal.valueOf(3));

        assertEquals(0L, duration.setupSeconds());
        assertEquals(300L, duration.runSeconds());
    }

    @Test
    void nullQuantityProducesSetupOnly() {
        SchedulingResource resource = resource(
                "SECONDS_PER_UNIT", BigDecimal.ONE, null, BigDecimal.ONE);

        TaskDuration duration = DurationCalculator.calculate(
                resource, operation(BigDecimal.valueOf(60), BigDecimal.valueOf(60)), null);

        assertEquals(60L, duration.setupSeconds());
        assertEquals(0L, duration.runSeconds());
    }

    @Test
    void invalidCapacityValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("UNIT_PER_HOUR", BigDecimal.ZERO, null, BigDecimal.ONE),
                operation(null, null), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("SECONDS_PER_UNIT", BigDecimal.ONE, null, BigDecimal.ONE),
                operation(BigDecimal.ZERO, null), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("BATCH_SECONDS", BigDecimal.ZERO, 10, BigDecimal.ONE),
                operation(null, null), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("BATCH_SECONDS", BigDecimal.ONE, 0, BigDecimal.ONE),
                operation(null, null), BigDecimal.ONE));
    }

    @Test
    void invalidModeOrEfficiencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("UNKNOWN", BigDecimal.ONE, null, BigDecimal.ONE),
                operation(null, null), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("UNIT_PER_HOUR", BigDecimal.ONE, null, BigDecimal.ZERO),
                operation(null, null), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> DurationCalculator.calculate(
                resource("UNIT_PER_HOUR", BigDecimal.ONE, null, BigDecimal.ONE),
                operation(null, BigDecimal.valueOf(-1)), BigDecimal.ONE));
    }

    private static SchedulingResource resource(
            String capacityMode,
            BigDecimal capacityValue,
            Integer batchCapacity,
            BigDecimal efficiency) {
        return new SchedulingResource(
                UUID.randomUUID(),
                "R-001",
                "PRIMARY",
                null,
                capacityMode,
                capacityValue,
                batchCapacity,
                efficiency,
                null,
                "ACTIVE");
    }

    private static SchedulingOperation operation(BigDecimal standardSeconds, BigDecimal setupSeconds) {
        return new SchedulingOperation(
                10,
                "OP-10",
                "工序 10",
                null,
                standardSeconds,
                setupSeconds,
                null,
                null,
                List.of(),
                false);
    }
}
