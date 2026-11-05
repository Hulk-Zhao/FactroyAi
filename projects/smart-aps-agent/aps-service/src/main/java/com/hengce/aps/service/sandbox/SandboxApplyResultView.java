package com.hengce.aps.service.sandbox;

import com.hengce.aps.service.plan.PlanActionView;

/**
 * 场景应用结果（C32 apply 响应）。
 *
 * @param sandbox 提升后的场景摘要
 * @param plan 本次应用生成的计划版本，复用 C24 {@code PlanActionView}（字段名一字不改）
 */
public record SandboxApplyResultView(SandboxSummaryView sandbox, PlanActionView plan) {
}
