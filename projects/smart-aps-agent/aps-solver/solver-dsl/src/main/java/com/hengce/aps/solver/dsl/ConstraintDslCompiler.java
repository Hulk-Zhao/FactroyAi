package com.hengce.aps.solver.dsl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hengce.aps.solver.api.ConstraintSpec;

/**
 * DSL 到求解器约束快照的编译器。
 *
 * <p>编译结果只描述软约束权重与参数，硬约束语义由求解器内部的硬约束流保证，
 * 因此 DSL 不得声明 HARD 规则。</p>
 */
public final class ConstraintDslCompiler {

    /**
     * 生效阶段：优化阶段。
     */
    public static final String PHASE_OPTIMIZATION = "OPTIMIZATION";

    /**
     * 约束类型：软约束。
     */
    public static final String TYPE_SOFT = "SOFT";

    /**
     * 编译为约束快照列表。
     *
     * @param document DSL 文档
     * @return 约束快照列表
     */
    public List<ConstraintSpec> compile(ConstraintDslDocument document) {
        List<ConstraintSpec> specs = new ArrayList<>();
        for (ConstraintDslSpec spec : document.constraints()) {
            Map<String, Object> params = spec.params();
            specs.add(new ConstraintSpec(spec.ruleCode(), TYPE_SOFT, BigDecimal.valueOf(spec.weight()),
                    false, PHASE_OPTIMIZATION, params));
        }
        return specs;
    }
}
