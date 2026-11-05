package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 工序快照；{@code standardSeconds}/{@code setupSeconds} 已按 C2 的覆盖优先级取值。
 *
 * <p>C12 §17.2 增加 {@code predecessors}：为空表示无显式依赖，求解流按
 * {@code operationIndex} 升序补默认 FS、零滞后，并标记 {@code dependencyFallback}。</p>
 *
 * @param operationIndex 工序序号，等于 {@code routing_node.seq_no}
 * @param operationCode 工序编码
 * @param operationName 工序名称
 * @param operationTemplateId 工序模板标识，可空
 * @param standardSeconds 单件标准工时（秒）
 * @param setupSeconds 换型/准备工时（秒）
 * @param primaryResourceId 主资源标识，可空
 * @param primaryResourceGroupId 主资源组标识，可空
 * @param secondaryResourceIds 辅助资源标识
 * @param optional 是否为可选工序
 * @param predecessors 同订单内的前序依赖
 */
public record SchedulingOperation(
        int operationIndex,
        String operationCode,
        String operationName,
        UUID operationTemplateId,
        BigDecimal standardSeconds,
        BigDecimal setupSeconds,
        UUID primaryResourceId,
        UUID primaryResourceGroupId,
        List<UUID> secondaryResourceIds,
        boolean optional,
        List<OperationDependency> predecessors
) {

    /**
     * 归一化辅助资源与前序依赖列表。
     */
    public SchedulingOperation {
        secondaryResourceIds = List.copyOf(
                secondaryResourceIds == null ? List.of() : secondaryResourceIds);
        predecessors = List.copyOf(predecessors == null ? List.of() : predecessors);
    }

    /**
     * 兼容 C12 之前的调用方，等价于「无显式依赖」。
     *
     * @param operationIndex 工序序号
     * @param operationCode 工序编码
     * @param operationName 工序名称
     * @param operationTemplateId 工序模板标识
     * @param standardSeconds 单件标准工时（秒）
     * @param setupSeconds 换型/准备工时（秒）
     * @param primaryResourceId 主资源标识
     * @param primaryResourceGroupId 主资源组标识
     * @param secondaryResourceIds 辅助资源标识
     * @param optional 是否为可选工序
     */
    public SchedulingOperation(
            int operationIndex,
            String operationCode,
            String operationName,
            UUID operationTemplateId,
            BigDecimal standardSeconds,
            BigDecimal setupSeconds,
            UUID primaryResourceId,
            UUID primaryResourceGroupId,
            List<UUID> secondaryResourceIds,
            boolean optional) {
        this(operationIndex, operationCode, operationName, operationTemplateId, standardSeconds,
                setupSeconds, primaryResourceId, primaryResourceGroupId, secondaryResourceIds,
                optional, List.of());
    }
}
