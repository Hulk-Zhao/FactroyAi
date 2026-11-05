package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.service.strategy.StrategyCreateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 创建策略请求。
 *
 * @param name 策略名称
 * @param direction 排产方向
 * @param strategyVersion 业务版本
 * @param effectiveFrom 生效日期
 * @param effectiveTo 失效日期
 * @param initialRules 初排规则对象
 * @param applyCondition 适用条件
 * @param rules 8 条软约束规则
 */
public record StrategyCreateRequest(
        @NotBlank @Size(max = 128) String name,
        String direction,
        @Min(1) Integer strategyVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Map<String, Object> initialRules,
        Map<String, Object> applyCondition,
        @Valid List<StrategyRuleRequest> rules) {

    /**
     * 转换为服务层命令。
     *
     * @return 创建命令
     */
    public StrategyCreateCommand toCommand() {
        return new StrategyCreateCommand(name, direction, strategyVersion, effectiveFrom, effectiveTo,
                initialRules, applyCondition,
                rules == null ? null : rules.stream().map(StrategyRuleRequest::toCommand).toList());
    }
}
