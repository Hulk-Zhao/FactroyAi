package com.hengce.aps.solver.timefold.dependency;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工序依赖约束式的纯函数测试：FS / SS / FF 正向与违反场景，SF 明确拒绝。
 */
class OperationDependencyEvaluatorTest {

    private static final LocalDateTime PREDECESSOR_START = LocalDateTime.of(2026, 1, 5, 8, 0);
    private static final LocalDateTime PREDECESSOR_END = LocalDateTime.of(2026, 1, 5, 10, 0);

    @Test
    void finishStartIsSatisfiedAtBoundaryAndViolatedBefore() {
        assertFalse(OperationDependencyEvaluator.violated(
                "FS", 30,
                PREDECESSOR_START, PREDECESSOR_END,
                PREDECESSOR_END.plusMinutes(30), PREDECESSOR_END.plusMinutes(90)));
        assertTrue(OperationDependencyEvaluator.violated(
                "FS", 30,
                PREDECESSOR_START, PREDECESSOR_END,
                PREDECESSOR_END.plusMinutes(29), PREDECESSOR_END.plusMinutes(89)));
    }

    @Test
    void startStartIsSatisfiedAtBoundaryAndViolatedBefore() {
        assertFalse(OperationDependencyEvaluator.violated(
                "SS", 60,
                PREDECESSOR_START, PREDECESSOR_END,
                PREDECESSOR_START.plusMinutes(60), PREDECESSOR_START.plusMinutes(120)));
        assertTrue(OperationDependencyEvaluator.violated(
                "SS", 60,
                PREDECESSOR_START, PREDECESSOR_END,
                PREDECESSOR_START.plusMinutes(59), PREDECESSOR_START.plusMinutes(119)));
    }

    @Test
    void finishFinishIsSatisfiedAtBoundaryAndViolatedBefore() {
        assertFalse(OperationDependencyEvaluator.violated(
                "FF", 0,
                PREDECESSOR_START, PREDECESSOR_END,
                PREDECESSOR_END.minusMinutes(60), PREDECESSOR_END));
        assertTrue(OperationDependencyEvaluator.violated(
                "FF", 0,
                PREDECESSOR_START, PREDECESSOR_END,
                PREDECESSOR_END.minusMinutes(61), PREDECESSOR_END.minusMinutes(1)));
    }

    @Test
    void startFinishIsRejectedInsteadOfDowngraded() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> OperationDependencyEvaluator.normalizeType("sf"));
        assertTrue(exception.getMessage().contains("SF"));
    }

    @Test
    void unknownOrBlankTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OperationDependencyEvaluator.normalizeType("XY"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationDependencyEvaluator.normalizeType(" "));
        assertThrows(IllegalArgumentException.class,
                () -> OperationDependencyEvaluator.normalizeType(null));
    }
}
