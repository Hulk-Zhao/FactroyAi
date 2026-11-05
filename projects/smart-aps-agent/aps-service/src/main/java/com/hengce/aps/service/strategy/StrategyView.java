package com.hengce.aps.service.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 策略视图；列表与详情都携带规则摘要 {@code ruleCount}/{@code weightSum}，
 * 详情与列表项同时携带完整 {@code rules}，供前端展示规则数/权重和与规则明细（C19）。
 *
 * @param id 策略主键
 * @param name 策略名称
 * @param direction 排产方向
 * @param strategyVersion 业务版本
 * @param initialRules 初排规则对象
 * @param optimizationRules 优化规则权重摘要
 * @param effectiveFrom 生效日期
 * @param effectiveTo 失效日期
 * @param applyCondition 适用条件
 * @param rules 规则明细
 * @param ruleCount 规则数
 * @param weightSum 权重合计
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record StrategyView(
        UUID id,
        String name,
        String direction,
        Integer strategyVersion,
        Map<String, Object> initialRules,
        Map<String, Object> optimizationRules,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Map<String, Object> applyCondition,
        List<StrategyRuleView> rules,
        int ruleCount,
        BigDecimal weightSum,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
