package com.hengce.aps.service.masterdata.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 新建订单命令，字段与 orders.yaml 的 OrderCreateRequest 对齐。
 *
 * @param orderNo 订单业务编码
 * @param source 订单来源，为空时默认 MANUAL
 * @param materialCode 产品物料编码
 * @param qty 数量
 * @param dueDate 交期
 * @param priority 优先级，为空时默认 NORMAL
 * @param customerId 客户主键，可为空
 * @param routingId 指定工艺路线主键，可为空
 * @param remark 备注
 * @param extAttrs 扩展属性
 */
public record OrderCreateCommand(
        String orderNo,
        OrderSource source,
        String materialCode,
        BigDecimal qty,
        LocalDate dueDate,
        OrderPriority priority,
        UUID customerId,
        UUID routingId,
        String remark,
        Map<String, Object> extAttrs) {
}
