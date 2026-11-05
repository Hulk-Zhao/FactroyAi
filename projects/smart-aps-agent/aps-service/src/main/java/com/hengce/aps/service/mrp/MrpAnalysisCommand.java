package com.hengce.aps.service.mrp;

import java.util.List;
import java.util.UUID;

/**
 * MRP 齐套分析命令，对应 {@code POST /api/v1/mrp/analysis} 的请求体。
 *
 * @param sandboxId 排产场景标识，必填；M2 仅回显，不参与物料计算
 * @param orderNos 指定订单号；空表示分析全部可排产订单
 * @param includeSuggestions 是否输出补料建议
 * @param blocking 是否按缺料阻塞排产
 */
public record MrpAnalysisCommand(UUID sandboxId, List<String> orderNos, boolean includeSuggestions,
                                 boolean blocking) {
}
