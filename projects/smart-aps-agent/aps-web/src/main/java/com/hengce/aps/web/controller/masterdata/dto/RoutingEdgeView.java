package com.hengce.aps.web.controller.masterdata.dto;

/**
 * 工艺路线边响应体。
 *
 * @param from 旧字段，前序 node_key
 * @param to 旧字段，后继 node_key
 * @param fromSeqNo 前序节点 seqNo
 * @param toSeqNo 后继节点 seqNo
 * @param dependType 依赖类型
 * @param lagMinutes 滞后分钟
 * @param minLagMinutes 旧字段，等价 lagMinutes
 */
public record RoutingEdgeView(
        String from,
        String to,
        int fromSeqNo,
        int toSeqNo,
        String dependType,
        int lagMinutes,
        int minLagMinutes) {
}
