package com.hengce.aps.service.plan;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多策略择优响应（C35 §25.7.2）。
 *
 * <p>{@code items} 按 {@code SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC} 排序，
 * {@code recommendedPlanId} 恒为排序第一项；{@code metrics} 为 {@code solve_task.metrics}
 * 原样透传（仅剔除 §24.2 已冻结不再输出的 {@code stepCount}/{@code unimprovedStepCount}）。</p>
 *
 * @param items 对比项，已按选优规则排序
 * @param recommendedPlanId 推荐计划，即排序第一项
 * @param tieBreak 并列处理规则字面量
 */
public record PlanCompareView(List<PlanCompareItem> items, UUID recommendedPlanId, String tieBreak) {

    /**
     * 冻结的并列处理规则字面量。
     */
    public static final String TIE_BREAK = "SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC";

    /**
     * 单个计划版本对比项。
     *
     * @param planId 计划版本主键
     * @param planNo 计划业务号
     * @param version 版本号
     * @param scoreHard 硬分
     * @param scoreSoft 软分
     * @param taskCount 快照任务数
     * @param metrics solve_task.metrics 原样透传
     */
    public record PlanCompareItem(
            UUID planId,
            String planNo,
            int version,
            Long scoreHard,
            Long scoreSoft,
            int taskCount,
            Map<String, Object> metrics) {
    }
}
