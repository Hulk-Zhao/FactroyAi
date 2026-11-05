package com.hengce.aps.solver.timefold.dependency;

import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖图构建：显式依赖保留、缺省补 FS/0 降级、非法输入拒绝。
 */
class DependencyGraphBuilderTest {

    @Test
    void explicitDependenciesKeepTypeAndLag() {
        SchedulingOrder order = order(
                operation(10, List.of()),
                operation(20, List.of(new OperationDependency(10, "ss", 15))),
                operation(30, List.of(new OperationDependency(20, "ff", 5))));

        DependencyGraph graph = DependencyGraphBuilder.build(List.of(order));

        assertFalse(graph.fallback());
        assertEquals(2, graph.edges().size());
        TaskDependency first = graph.edges().get(0);
        assertEquals("SS", first.dependType());
        assertEquals(15, first.lagMinutes());
        TaskDependency second = graph.edges().get(1);
        assertEquals("FF", second.dependType());
        assertEquals(5, second.lagMinutes());
    }

    @Test
    void emptyPredecessorsFallBackToSequentialFinishStart() {
        SchedulingOrder order = order(
                operation(10, List.of()),
                operation(20, List.of()),
                operation(30, List.of()));

        DependencyGraph graph = DependencyGraphBuilder.build(List.of(order));

        assertTrue(graph.fallback());
        assertEquals(2, graph.edges().size());
        assertTrue(graph.edges().stream().allMatch(edge -> "FS".equals(edge.dependType())));
        assertTrue(graph.edges().stream().allMatch(edge -> edge.lagMinutes() == 0));
    }

    @Test
    void startFinishDependencyIsRejected() {
        SchedulingOrder order = order(
                operation(10, List.of()),
                operation(20, List.of(new OperationDependency(10, "SF", 0))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DependencyGraphBuilder.build(List.of(order)));

        assertTrue(exception.getMessage().contains("SF"));
    }

    @Test
    void negativeLagIsRejected() {
        SchedulingOrder order = order(
                operation(10, List.of()),
                operation(20, List.of(new OperationDependency(10, "FS", -5))));

        assertThrows(IllegalArgumentException.class,
                () -> DependencyGraphBuilder.build(List.of(order)));
    }

    @Test
    void missingPredecessorIsRejected() {
        SchedulingOrder order = order(
                operation(10, List.of()),
                operation(20, List.of(new OperationDependency(99, "FS", 0))));

        assertThrows(IllegalArgumentException.class,
                () -> DependencyGraphBuilder.build(List.of(order)));
    }

    @Test
    void dependencyCycleIsRejected() {
        SchedulingOrder order = order(
                operation(10, List.of(new OperationDependency(20, "FS", 0))),
                operation(20, List.of(new OperationDependency(10, "FS", 0))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DependencyGraphBuilder.build(List.of(order)));

        assertTrue(exception.getMessage().contains("环"));
    }

    private static SchedulingOrder order(SchedulingOperation... operations) {
        return new SchedulingOrder(
                UUID.randomUUID(),
                "SO-EDGE",
                "MAT-EDGE",
                BigDecimal.valueOf(10),
                LocalDate.now(ZoneOffset.UTC).plusDays(3),
                "NORMAL",
                "UNFROZEN",
                UUID.randomUUID(),
                List.of(operations));
    }

    private static SchedulingOperation operation(
            int operationIndex, List<OperationDependency> predecessors) {
        return new SchedulingOperation(
                operationIndex,
                "OP-" + operationIndex,
                "工序 " + operationIndex,
                null,
                BigDecimal.valueOf(600),
                BigDecimal.valueOf(600),
                UUID.randomUUID(),
                null,
                List.of(),
                false,
                predecessors);
    }
}
