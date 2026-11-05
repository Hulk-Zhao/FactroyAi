package com.hengce.aps.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 生产订单领域模型，字段命名对齐 docs/db/schema.md 的 orders 表。
 *
 * @param orderNo 订单号
 * @param materialCode 产品物料编码（对应 material.code，展示用）
 * @param qty 数量
 * @param status 订单状态
 * @param freezeStatus 冻结状态
 * @param dueDate 交期
 */
public record ProductionOrder(
        String orderNo,
        String materialCode,
        BigDecimal qty,
        OrderStatus status,
        FreezeStatus freezeStatus,
        LocalDate dueDate) {
}
