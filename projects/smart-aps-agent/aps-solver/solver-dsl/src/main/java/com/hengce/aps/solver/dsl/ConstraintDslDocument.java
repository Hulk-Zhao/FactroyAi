package com.hengce.aps.solver.dsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 约束 DSL 文档（规范化模型）。
 *
 * <p>序列化永远输出数组 {@code constraints}；反序列化兼容单数 {@code constraint}
 * （仅当启用 {@code aps.dsl.legacy-singular}，保留到 M2 结束）。</p>
 *
 * @param version DSL 版本号，从 1 开始
 * @param constraints 规则列表，M2 固定 8 条
 */
public record ConstraintDslDocument(int version, List<ConstraintDslSpec> constraints) {

    /**
     * 规范化构造：保证列表不可变且元素非空。
     *
     * @param version DSL 版本号
     * @param constraints 规则列表
     */
    public ConstraintDslDocument {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    /**
     * 权重映射（编码到权重）。
     *
     * @return 权重映射，按文档顺序
     */
    public Map<String, Integer> weights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (ConstraintDslSpec spec : constraints) {
            weights.put(spec.ruleCode(), spec.weight());
        }
        return Collections.unmodifiableMap(weights);
    }

    /**
     * 默认文档：8 条规则全部使用默认权重。
     *
     * @return 默认 DSL 文档
     */
    public static ConstraintDslDocument standard() {
        List<ConstraintDslSpec> specs = new ArrayList<>();
        for (RuleCode ruleCode : RuleCode.values()) {
            specs.add(new ConstraintDslSpec(ruleCode.name(), ruleCode.getDefaultWeight(), Map.of()));
        }
        return new ConstraintDslDocument(1, specs);
    }
}
