package com.hengce.aps.service.mrp;

import java.util.List;
import java.util.UUID;

/**
 * MRP 采购建议查询命令，对应 {@code GET /api/v1/mrp/suggestions} 的查询参数（C34）。
 *
 * @param sandboxId 排产场景标识，必填；缺失时由服务层抛 {@code 40000}，不兜底 BASELINE
 * @param orderNos 指定订单号；空表示该场景全部可排产订单
 * @param horizonDays 需求时间窗口（1–365 天），空按默认 30 天
 */
public record MrpSuggestionQuery(UUID sandboxId, List<String> orderNos, Integer horizonDays) {
}
