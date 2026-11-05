package com.hengce.aps.service.schedule.atp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ATP 交期预估入参（服务端会再次校验，非法参数抛 422/42200）。
 *
 * <p>所有字段允许为空，以便把“字段缺失/类型不合法”统一收敛为契约错误码 42200，
 * 而不是被 Bean Validation 提前转换成 400。校验入口见 {@link AtpRequestValidator}。</p>
 *
 * @param materialCode    物料编码（必填）
 * @param qty             数量（必填，>0）
 * @param requestedDate   客户请求交期（必填，作为交期偏好）
 * @param customerId      客户主键（可选，透传）
 * @param orderNo         已有订单号（可选；存在时校验订单与状态）
 * @param mode            FAST/ACCURATE，缺省 FAST
 * @param timeoutSeconds  ACCURATE 限时秒数，缺省取配置值
 * @param allowSplitBatch 是否允许拆批建议，缺省 false
 */
public record AtpQuery(
        String materialCode,
        BigDecimal qty,
        LocalDate requestedDate,
        UUID customerId,
        String orderNo,
        String mode,
        Integer timeoutSeconds,
        Boolean allowSplitBatch) {
}
