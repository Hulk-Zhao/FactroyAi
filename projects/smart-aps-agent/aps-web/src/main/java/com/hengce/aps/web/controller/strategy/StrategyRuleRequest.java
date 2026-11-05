package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.service.strategy.StrategyRuleCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 策略规则请求，字段形状对齐 C19 与前端 W8 策略页。
 *
 * @param code 规则编码
 * @param weight 权重
 * @param params 规则参数
 * @param enabled 是否启用
 */
public record StrategyRuleRequest(
        @NotBlank String code,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal weight,
        Map<String, Object> params,
        Boolean enabled) {

    /**
     * 转换为服务层命令。
     *
     * @return 规则命令
     */
    public StrategyRuleCommand toCommand() {
        return new StrategyRuleCommand(code, weight, params, enabled);
    }
}
