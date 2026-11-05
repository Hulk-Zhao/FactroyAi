package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.service.strategy.StrategyCloneCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 克隆策略请求。
 *
 * @param name 新策略名称
 * @param strategyVersion 新业务版本
 * @param effectiveFrom 新生效日期
 */
public record StrategyCloneRequest(
        @Size(max = 128) String name,
        @Min(1) Integer strategyVersion,
        LocalDate effectiveFrom) {

    /**
     * 转换为服务层命令。
     *
     * @return 克隆命令
     */
    public StrategyCloneCommand toCommand() {
        return new StrategyCloneCommand(name, strategyVersion, effectiveFrom);
    }
}
