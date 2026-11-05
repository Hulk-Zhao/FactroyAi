package com.hengce.aps.solver.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 约束归因，用于 Agent 解释与甘特提示；{@code taskIds} 指向 {@link TaskAssignment#taskId()}。
 *
 * <p>M2-SE-05 增补两个可选字段，用于“某次求解为什么不好”的量化解释：
 * {@code weight}（软约束求解时生效权重，硬约束为空）与
 * {@code deduction}（软约束扣分 = 权重 × 违反量，正数；硬约束为 0）。
 * 这两个字段是契约缺口下的最小扩展，规则键仍为 {@code constraintCode}（RuleCode）。</p>
 *
 * @param constraintCode 约束编码（软约束使用 RuleCode，如 {@code MIN_TARDINESS}）
 * @param type 约束类型：HARD / SOFT
 * @param taskIds 受影响任务的 {@code taskId}
 * @param description 面向人的说明
 * @param weight 软约束权重；硬约束为空
 * @param deduction 软约束扣分；硬约束为 0
 */
public record ConstraintJustification(
        String constraintCode,
        String type,
        List<Long> taskIds,
        String description,
        Integer weight,
        BigDecimal deduction
) {

    /**
     * 兼容 M1 的硬约束归因构造：权重为空、扣分为 0。
     *
     * @param constraintCode 约束编码
     * @param type 约束类型
     * @param taskIds 受影响任务
     * @param description 面向人的说明
     */
    public ConstraintJustification(
            String constraintCode,
            String type,
            List<Long> taskIds,
            String description) {
        this(constraintCode, type, taskIds, description, null, BigDecimal.ZERO);
    }

    /**
     * 归一化任务标识列表与扣分。
     */
    public ConstraintJustification {
        taskIds = List.copyOf(taskIds == null ? List.of() : taskIds);
        deduction = deduction == null ? BigDecimal.ZERO : deduction;
    }
}
