package com.hengce.aps.web.controller.masterdata.dto;

import java.util.List;
import java.util.UUID;

/**
 * 工艺路线节点响应体。
 *
 * @param seqNo 节点序号
 * @param operationTemplateId 工序模版 UUID
 * @param operationCode 工序编码
 * @param operationName 工序名称
 * @param standardSeconds 标准加工秒数
 * @param setupSeconds 换型秒数
 * @param primaryResourceMode 主资源模式 INHERIT/FLOAT/FIXED（由 overrides 判定后回显）
 * @param primaryResourceId 主资源 UUID（来自 overrides）
 * @param primaryResourceCode 主资源编码（只读回显）
 * @param secondaryResourceIds 辅助资源 UUID 列表
 * @param optional 是否可选工序
 * @param sequence 旧字段，等价 seqNo
 * @param resourceCode 旧字段，等价 primaryResourceCode
 */
public record RoutingNodeView(
        int seqNo,
        UUID operationTemplateId,
        String operationCode,
        String operationName,
        Integer standardSeconds,
        Integer setupSeconds,
        String primaryResourceMode,
        UUID primaryResourceId,
        String primaryResourceCode,
        List<UUID> secondaryResourceIds,
        boolean optional,
        Integer sequence,
        String resourceCode) {
}
