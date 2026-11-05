package com.hengce.aps.service.strategy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 创建策略命令。
 *
 * @param name 策略名称
 * @param direction 排产方向：FORWARD / BACKWARD / BOTTLENECK，空时默认 FORWARD
 * @param strategyVersion 业务版本，空时默认 1
 * @param effectiveFrom 生效日期，空时默认当天
 * @param effectiveTo 失效日期，可空
 * @param initialRules 初排规则对象
 * @param applyCondition 适用条件对象
 * @param rules 优化规则；为空时使用 8 条默认权重
 */
public record StrategyCreateCommand(
        String name,
        String direction,
        Integer strategyVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Map<String, Object> initialRules,
        Map<String, Object> applyCondition,
        List<StrategyRuleCommand> rules) {
}
