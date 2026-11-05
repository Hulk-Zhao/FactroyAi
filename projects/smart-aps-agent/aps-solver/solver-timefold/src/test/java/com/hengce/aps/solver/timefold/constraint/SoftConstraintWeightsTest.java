package com.hengce.aps.solver.timefold.constraint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 软约束权重开关（activate/disable）与合法性校验单测。
 */
class SoftConstraintWeightsTest {

    @AfterEach
    void tearDown() {
        SoftConstraintWeights.disable();
    }

    @Test
    void activateShouldEnableAllEightRules() {
        SoftConstraintWeights.activate(validWeights());

        assertTrue(SoftConstraintWeights.isEnabled());
        assertEquals(8, SoftConstraintWeights.active().size());
        assertEquals(40, SoftConstraintWeights.weightOf(ConstraintCodes.MIN_TARDINESS));
    }

    @Test
    void disableShouldFallBackToM1HardOnlyBaseline() {
        SoftConstraintWeights.activate(validWeights());
        SoftConstraintWeights.disable();

        assertFalse(SoftConstraintWeights.isEnabled());
        assertTrue(SoftConstraintWeights.active().isEmpty());
        assertEquals(0, SoftConstraintWeights.weightOf(ConstraintCodes.MIN_TARDINESS));
    }

    @Test
    void missingRuleShouldBeRejectedWithoutTakingEffect() {
        Map<String, Integer> weights = validWeights();
        weights.remove(ConstraintCodes.MIN_MAKESPAN);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SoftConstraintWeights.activate(weights));

        assertEquals("软约束权重必须覆盖 8 条核心规则", exception.getMessage());
        assertFalse(SoftConstraintWeights.isEnabled());
    }

    @Test
    void wrongTotalShouldBeRejectedWithoutTakingEffect() {
        Map<String, Integer> weights = validWeights();
        weights.put(ConstraintCodes.MIN_MAKESPAN, 5);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SoftConstraintWeights.activate(weights));

        assertEquals("软约束权重之和必须为 100，实际为 99", exception.getMessage());
        assertFalse(SoftConstraintWeights.isEnabled());
    }

    @Test
    void outOfRangeWeightShouldBeRejected() {
        Map<String, Integer> weights = validWeights();
        weights.put(ConstraintCodes.MIN_TARDINESS, 101);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SoftConstraintWeights.activate(weights));

        assertEquals("软约束权重越界: MIN_TARDINESS=101", exception.getMessage());
        assertFalse(SoftConstraintWeights.isEnabled());
    }

    @Test
    void defaultsShouldAlsoSumToHundred() {
        Map<String, Integer> defaults = SoftConstraintWeights.defaults();

        assertEquals(8, defaults.size());
        assertEquals(100, defaults.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static Map<String, Integer> validWeights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put(ConstraintCodes.MIN_TARDINESS, 40);
        weights.put(ConstraintCodes.EDD, 6);
        weights.put(ConstraintCodes.SPT, 8);
        weights.put(ConstraintCodes.SAME_PRODUCT_CONTINUOUS, 12);
        weights.put(ConstraintCodes.MIN_SETUP_TIME, 12);
        weights.put(ConstraintCodes.BALANCE_LOAD, 2);
        weights.put(ConstraintCodes.PRIORITY_RESPECT, 14);
        weights.put(ConstraintCodes.MIN_MAKESPAN, 6);
        return weights;
    }
}
