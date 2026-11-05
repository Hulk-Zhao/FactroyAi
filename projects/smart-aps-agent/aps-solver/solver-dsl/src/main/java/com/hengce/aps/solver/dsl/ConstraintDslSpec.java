package com.hengce.aps.solver.dsl;

import java.util.Map;

/**
 * DSL 中的单条规则声明。
 *
 * @param ruleCode 白名单内的规则编码
 * @param weight 权重，0..100
 * @param params 规则参数，只允许 {@link RuleCode#getAllowedParams()} 声明的键
 */
public record ConstraintDslSpec(String ruleCode, int weight, Map<String, Object> params) {

    /**
     * 规范化构造：参数不可变为空映射。
     *
     * @param ruleCode 规则编码
     * @param weight 权重
     * @param params 参数
     */
    public ConstraintDslSpec {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
