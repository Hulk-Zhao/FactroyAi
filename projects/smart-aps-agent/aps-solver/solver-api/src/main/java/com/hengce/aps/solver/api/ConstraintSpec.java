package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 约束快照；M1 只透传 DSL 加载结果，不在求解器内解释规则语义。
 *
 * @param code 约束编码
 * @param type 约束类型：HARD / SOFT
 * @param weight 权重，可空
 * @param hard 是否硬约束
 * @param phase 生效阶段：INITIAL / OPTIMIZATION
 * @param params 约束参数
 */
public record ConstraintSpec(
        String code,
        String type,
        BigDecimal weight,
        boolean hard,
        String phase,
        Map<String, Object> params
) {
}
