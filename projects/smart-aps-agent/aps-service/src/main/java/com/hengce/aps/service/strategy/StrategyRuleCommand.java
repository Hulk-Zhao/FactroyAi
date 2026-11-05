package com.hengce.aps.service.strategy;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 策略规则命令。
 *
 * @param code 规则编码，必须是 8 条白名单之一
 * @param weight 权重，0..100，8 条合计必须为 100
 * @param params 规则参数，只允许白名单参数键
 * @param enabled 是否启用；为空时按权重是否大于 0 推断
 */
public record StrategyRuleCommand(String code, BigDecimal weight, Map<String, Object> params, Boolean enabled) {
}
