package com.hengce.aps.service.strategy;

import java.util.List;
import java.util.Map;

/**
 * 策略导出载荷（C19 冻结形状）：{@code data = {strategy, rules, checksum}}。
 *
 * <p>{@code strategy} 为策略业务字段（含 {@code id/version}，导入时忽略）；
 * {@code rules} 为 8 条规则数组；{@code checksum} 必出现，是对规范化 {@code {strategy, rules}}
 * 计算的 SHA-256（64 位小写十六进制），规范化规则见 {@link StrategyCanonicalJson}。</p>
 *
 * @param strategy 策略业务字段
 * @param rules 规则数组
 * @param checksum 规范化 {@code {strategy, rules}} 的 SHA-256
 */
public record StrategyExportBundle(
        Map<String, Object> strategy,
        List<StrategyRuleCommand> rules,
        String checksum) {
}
