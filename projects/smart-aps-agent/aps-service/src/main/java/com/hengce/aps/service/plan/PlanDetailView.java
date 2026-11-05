package com.hengce.aps.service.plan;

import java.util.List;
import java.util.Map;

/**
 * 计划版本详情（GET /plans/{id}）。
 *
 * @param action 版本元信息
 * @param tasks 该版本快照内的任务（只读，来自 snapshot，不随 task 表变化）
 */
public record PlanDetailView(PlanActionView action, List<Map<String, Object>> tasks) {
}
