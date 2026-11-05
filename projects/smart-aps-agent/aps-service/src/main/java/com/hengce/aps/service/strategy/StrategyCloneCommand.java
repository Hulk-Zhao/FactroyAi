package com.hengce.aps.service.strategy;

import java.time.LocalDate;

/**
 * 克隆策略命令。
 *
 * @param name 新策略名称，空时沿用源名称
 * @param strategyVersion 新业务版本，空时取同名策略下一个可用版本
 * @param effectiveFrom 新生效日期，空时沿用源生效日期
 */
public record StrategyCloneCommand(String name, Integer strategyVersion, LocalDate effectiveFrom) {
}
