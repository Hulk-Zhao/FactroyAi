package com.hengce.aps.solver.timefold.dependency;

import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 {@code SchedulingOperation.predecessors} 展开为依赖边，并完成 M1 校验：
 *
 * <ul>
 *   <li>仅支持 FS/SS/FF；SF 或未知类型直接拒绝整题；</li>
 *   <li>滞后不能为负；前序工序必须存在于同一订单；</li>
 *   <li>依赖环直接拒绝，不做隐式断环；</li>
 *   <li>{@code predecessors} 为空时按 {@code operationIndex} 升序补默认 FS/零滞后，并标记降级。</li>
 * </ul>
 */
public final class DependencyGraphBuilder {

    private DependencyGraphBuilder() {
    }

    /**
     * 构建依赖图。
     *
     * @param orders 订单快照
     * @return 依赖图与降级信息
     */
    public static DependencyGraph build(List<SchedulingOrder> orders) {
        List<TaskDependency> edges = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        boolean fallback = false;
        for (SchedulingOrder order : orders) {
            List<SchedulingOperation> operations = order.operations().stream()
                    .sorted(Comparator.comparingInt(SchedulingOperation::operationIndex))
                    .toList();
            Map<Integer, SchedulingOperation> operationByIndex = new LinkedHashMap<>();
            for (SchedulingOperation operation : operations) {
                operationByIndex.put(operation.operationIndex(), operation);
            }
            Map<Integer, List<Integer>> predecessorsByIndex = new LinkedHashMap<>();
            for (int index = 0; index < operations.size(); index++) {
                SchedulingOperation operation = operations.get(index);
                List<OperationDependency> predecessors =
                        normalize(order, operationByIndex, operation);
                if (predecessors.isEmpty() && index > 0) {
                    predecessors = List.of(new OperationDependency(
                            operations.get(index - 1).operationIndex(),
                            OperationDependencyEvaluator.FINISH_START,
                            0));
                    fallback = true;
                }
                predecessorsByIndex.put(
                        operation.operationIndex(),
                        predecessors.stream().map(OperationDependency::predecessorOperationIndex).toList());
                for (OperationDependency dependency : predecessors) {
                    TaskDependency edge = new TaskDependency(
                            planningId(order.orderId(), operation.operationIndex()),
                            planningId(order.orderId(), dependency.predecessorOperationIndex()),
                            dependency.dependType(),
                            dependency.lagMinutes());
                    if (seenEdges.add(edgeKey(edge))) {
                        edges.add(edge);
                    }
                }
            }
            detectCycle(order, predecessorsByIndex);
        }
        return new DependencyGraph(List.copyOf(edges), fallback);
    }

    /**
     * 任务 planningId，与计划实体保持同一口径。
     *
     * @param orderId 订单主键
     * @param operationIndex 工序序号
     * @return planningId
     */
    public static String planningId(java.util.UUID orderId, int operationIndex) {
        return String.valueOf(orderId) + ':' + operationIndex;
    }

    private static List<OperationDependency> normalize(
            SchedulingOrder order,
            Map<Integer, SchedulingOperation> operationByIndex,
            SchedulingOperation operation) {
        List<OperationDependency> normalized = new ArrayList<>();
        for (OperationDependency dependency : operation.predecessors()) {
            String dependType = OperationDependencyEvaluator.normalizeType(dependency.dependType());
            if (dependency.lagMinutes() < 0) {
                throw new IllegalArgumentException("工序依赖的 lagMinutes 不能为负数: "
                        + order.orderNo() + " / 工序 " + operation.operationIndex());
            }
            if (!operationByIndex.containsKey(dependency.predecessorOperationIndex())) {
                throw new IllegalArgumentException("工序依赖的前序工序不存在: "
                        + order.orderNo() + " / 工序 " + operation.operationIndex()
                        + " -> " + dependency.predecessorOperationIndex());
            }
            normalized.add(new OperationDependency(
                    dependency.predecessorOperationIndex(), dependType, dependency.lagMinutes()));
        }
        return List.copyOf(normalized);
    }

    private static void detectCycle(SchedulingOrder order, Map<Integer, List<Integer>> predecessorsByIndex) {
        Map<Integer, Integer> state = new HashMap<>();
        for (Integer node : predecessorsByIndex.keySet()) {
            visit(order, node, predecessorsByIndex, state);
        }
    }

    private static void visit(
            SchedulingOrder order,
            Integer node,
            Map<Integer, List<Integer>> predecessorsByIndex,
            Map<Integer, Integer> state) {
        int current = state.getOrDefault(node, 0);
        if (current == 1) {
            throw new IllegalArgumentException("工序依赖存在环: " + order.orderNo() + " / 工序 " + node);
        }
        if (current == 2) {
            return;
        }
        state.put(node, 1);
        for (Integer predecessor : predecessorsByIndex.getOrDefault(node, List.of())) {
            visit(order, predecessor, predecessorsByIndex, state);
        }
        state.put(node, 2);
    }

    private static String edgeKey(TaskDependency edge) {
        return edge.successorPlanningId() + '|' + edge.predecessorPlanningId()
                + '|' + edge.dependType() + '|' + edge.lagMinutes();
    }
}
