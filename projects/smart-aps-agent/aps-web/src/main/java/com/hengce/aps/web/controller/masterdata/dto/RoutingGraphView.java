package com.hengce.aps.web.controller.masterdata.dto;

import java.util.List;
import java.util.UUID;

/**
 * 工艺路线 DAG 响应体。
 *
 * @param routingId 路线 UUID
 * @param nodes 节点列表
 * @param edges 边列表
 */
public record RoutingGraphView(
        UUID routingId,
        List<RoutingNodeView> nodes,
        List<RoutingEdgeView> edges) {
}
