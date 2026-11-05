package com.hengce.aps.service.process.routing;

/**
 * 对外边视图数据，已完成 seqNo 映射。
 *
 * @param fromSeqNo 前序节点 seqNo
 * @param toSeqNo 后继节点 seqNo
 * @param fromNodeKey 前序节点 node_key
 * @param toNodeKey 后继节点 node_key
 * @param dependType 依赖类型
 * @param lagMinutes 滞后分钟
 */
public record RoutingEdgeData(
        int fromSeqNo,
        int toSeqNo,
        String fromNodeKey,
        String toNodeKey,
        String dependType,
        int lagMinutes) {
}
