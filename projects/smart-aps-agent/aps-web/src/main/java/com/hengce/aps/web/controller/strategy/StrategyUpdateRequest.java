package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.service.strategy.StrategyUpdateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 更新策略请求：PUT 语义为全量替换 rules（C19）。
 *
 * @param version 乐观锁版本
 * @param name 策略名称
 * @param direction 排产方向
 * @param effectiveFrom 生效日期
 * @param effectiveTo 失效日期
 * @param initialRules 初排规则对象
 * @param applyCondition 适用条件
 * @param rules 全量 8 条软约束规则
 */
public record StrategyUpdateRequest(
        @NotNull Integer version,
        @Size(max = 128) String name,
        String direction,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Map<String, Object> initialRules,
        Map<String, Object> applyCondition,
        @Valid List<StrategyRuleRequest> rules) {

    /**
     * 转换为服务层命令。
     *
     * @return 更新命令
     */
    public StrategyUpdateCommand toCommand() {
        return new StrategyUpdateCommand(version, name, direction, effectiveFrom, effectiveTo,
                initialRules, applyCondition,
                rules == null ? null : rules.stream().map(StrategyRuleRequest::toCommand).toList());
    }
}
