package com.hengce.aps.solver.api;

/**
 * 同订单内的工序依赖（contract-freeze C12 §17.2）。
 *
 * <p>{@code dependType} 取值 {@code FS / SS / FF}（V1 {@code chk_routing_edge_depend_type}）；
 * {@code SF} 仅保留语义，M1 遇到必须拒绝该问题，不得静默降级。
 * {@code lagMinutes} 在 M1 要求非负。</p>
 *
 * @param predecessorOperationIndex 前序工序序号，同订单内 {@code operationIndex}
 * @param dependType 依赖类型：FS / SS / FF
 * @param lagMinutes 滞后分钟数，M1 要求 >= 0
 */
public record OperationDependency(
        int predecessorOperationIndex,
        String dependType,
        int lagMinutes
) {
}
