package com.hengce.aps.service.strategy;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 策略规则视图，字段形状对齐前端 W8 已交付的策略页（C19）。
 *
 * @param code 规则编码
 * @param name 规则名称
 * @param phase 规则阶段：INITIAL / OPTIMIZATION
 * @param type 规则类型：HARD / SOFT
 * @param weight 权重
 * @param params 规则参数
 * @param enabled 是否启用
 * @param seqNo 排序号
 */
public record StrategyRuleView(
        String code,
        String name,
        String phase,
        String type,
        BigDecimal weight,
        Map<String, Object> params,
        Boolean enabled,
        Integer seqNo) {
}
