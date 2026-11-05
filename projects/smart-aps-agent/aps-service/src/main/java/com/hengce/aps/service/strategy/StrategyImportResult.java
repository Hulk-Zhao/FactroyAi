package com.hengce.aps.service.strategy;

import java.util.List;
import java.util.UUID;

/**
 * 策略导入响应（C19 形状）：{@code {valid, errors[], strategyId, existing}}。
 *
 * <p>{@code dryRun=true} 与真正导入共用同一校验器，返回同样的 {@code errors[]}；
 * {@code strategyId} 仅在 {@code dryRun=false} 且落库成功或幂等命中既有记录时非空。</p>
 *
 * <p>{@code existing} 为 M2 收口新增的<b>兼容性字段</b>（只增不改）：
 * {@code true} 表示 checksum 命中同名同版本的既有记录，本次未新建、未升版本、未写 rules；
 * {@code false} 表示新建（或 dryRun 下将新建）。</p>
 *
 * @param valid 是否通过校验
 * @param errors 校验错误列表，通过时为空数组
 * @param strategyId 落库成功或幂等命中时的策略主键；试运行或校验失败时为 {@code null}
 * @param existing 是否命中同名同版本的既有记录（幂等）
 */
public record StrategyImportResult(
        boolean valid,
        List<StrategyImportError> errors,
        UUID strategyId,
        boolean existing) {
}
