package com.hengce.aps.service.strategy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 策略导入导出载荷（JSON）。
 *
 * @param formatVersion 载荷格式版本，固定 {@code 1.0}
 * @param name 策略名称
 * @param direction 排产方向
 * @param strategyVersion 业务版本
 * @param effectiveFrom 生效日期
 * @param effectiveTo 失效日期
 * @param initialRules 初排规则对象
 * @param applyCondition 适用条件
 * @param rules 规则明细
 */
public record StrategyExportPayload(
        String formatVersion,
        String name,
        String direction,
        Integer strategyVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Map<String, Object> initialRules,
        Map<String, Object> applyCondition,
        List<StrategyRuleCommand> rules) {

    /**
     * 载荷格式版本。
     */
    public static final String FORMAT_VERSION = "1.0";
}
