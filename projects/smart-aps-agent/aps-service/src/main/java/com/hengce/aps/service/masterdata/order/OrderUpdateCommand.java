package com.hengce.aps.service.masterdata.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 更新订单命令，字段与 orders.yaml 的 OrderUpdateRequest 对齐。
 *
 * <p>未传字段为 null 时保持原值；{@code orderNo} 不在命令中，明确不可修改。</p>
 *
 * @param materialCode 产品物料编码
 * @param qty 数量
 * @param dueDate 交期
 * @param priority 优先级
 * @param customerId 客户主键
 * @param routingId 指定工艺路线主键
 * @param remark 备注
 * @param extAttrs 扩展属性
 * @param version 乐观锁版本，必填
 */
public record OrderUpdateCommand(
        String materialCode,
        BigDecimal qty,
        LocalDate dueDate,
        OrderPriority priority,
        UUID customerId,
        UUID routingId,
        String remark,
        Map<String, Object> extAttrs,
        Integer version) {
}
