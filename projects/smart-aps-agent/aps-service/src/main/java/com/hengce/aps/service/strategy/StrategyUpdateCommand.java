package com.hengce.aps.service.strategy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 更新策略命令。
 *
 * @param version 乐观锁版本，必填
 * @param name 策略名称，可空表示不变
 * @param direction 排产方向，可空表示不变
 * @param effectiveFrom 生效日期，可空表示不变
 * @param effectiveTo 失效日期，可空表示不变
 * @param initialRules 初排规则对象，可空表示不变
 * @param applyCondition 适用条件对象，可空表示不变
 * @param rules 优化规则，可空表示不变；非空时必须为完整 8 条且合计 100
 */
public record StrategyUpdateCommand(
        Integer version,
        String name,
        String direction,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Map<String, Object> initialRules,
        Map<String, Object> applyCondition,
        List<StrategyRuleCommand> rules) {
}
