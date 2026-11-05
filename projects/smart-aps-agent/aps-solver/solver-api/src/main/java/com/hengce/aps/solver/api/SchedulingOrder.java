package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 订单快照；{@code operations} 为该订单生效工艺路线的工序，
 * {@code operationIndex} 等于 {@code routing_node.seq_no}（C3）。
 *
 * <p>M2 W4 增加 {@code productFamily}：取自物料分类编码，供软约束
 * {@code SAME_PRODUCT_CONTINUOUS} 归组；为空表示未维护分类。
 * {@code dueDate}/{@code priority} 供 {@code MIN_TARDINESS}/{@code EDD}/
 * {@code PRIORITY_RESPECT} 使用，由 {@code TimefoldModelBuilder} 映射到计划任务。</p>
 *
 * @param orderId 订单主键
 * @param orderNo 订单号
 * @param materialCode 产品物料编码
 * @param productFamily 产品族（物料分类编码），可空
 * @param qty 订单数量
 * @param dueDate 交期日（不带时区）
 * @param priority 优先级：URGENT / HIGH / NORMAL / LOW
 * @param freezeStatus 冻结状态：UNFROZEN / FROZEN / LOCKED_TIME_WINDOW / LOCKED_RESOURCE
 * @param routingId 工艺路线标识
 * @param operations 工序快照
 * @param notBefore 跨窗口下界：窗口外已完成/冻结任务的最大结束时间（UTC），
 *                  本订单窗口内工序不得早于该时刻开始；为空表示无下界
 */
public record SchedulingOrder(
        UUID orderId,
        String orderNo,
        String materialCode,
        String productFamily,
        BigDecimal qty,
        LocalDate dueDate,
        String priority,
        String freezeStatus,
        UUID routingId,
        List<SchedulingOperation> operations,
        LocalDateTime notBefore
) {

    /**
     * 归一化工序列表。
     */
    public SchedulingOrder {
        operations = List.copyOf(operations == null ? List.of() : operations);
    }

    /**
     * 兼容 C21 之前的调用方，等价于「未维护产品族」。
     *
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param materialCode 产品物料编码
     * @param qty 订单数量
     * @param dueDate 交期日
     * @param priority 优先级
     * @param freezeStatus 冻结状态
     * @param routingId 工艺路线标识
     * @param operations 工序快照
     */
    public SchedulingOrder(
            UUID orderId,
            String orderNo,
            String materialCode,
            BigDecimal qty,
            LocalDate dueDate,
            String priority,
            String freezeStatus,
            UUID routingId,
            List<SchedulingOperation> operations) {
        this(orderId, orderNo, materialCode, null, qty, dueDate, priority, freezeStatus,
                routingId, operations, null);
    }

    /**
     * 兼容不含跨窗口下界的调用方。
     *
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param materialCode 产品物料编码
     * @param productFamily 产品族
     * @param qty 订单数量
     * @param dueDate 交期日
     * @param priority 优先级
     * @param freezeStatus 冻结状态
     * @param routingId 工艺路线标识
     * @param operations 工序快照
     */
    public SchedulingOrder(
            UUID orderId,
            String orderNo,
            String materialCode,
            String productFamily,
            BigDecimal qty,
            LocalDate dueDate,
            String priority,
            String freezeStatus,
            UUID routingId,
            List<SchedulingOperation> operations) {
        this(orderId, orderNo, materialCode, productFamily, qty, dueDate, priority, freezeStatus,
                routingId, operations, null);
    }
}
