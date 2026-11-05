package com.hengce.aps.service.process.routing;

import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 图读取结果：节点实体 + 已映射 seqNo 的边 + 资源编码回显表。
 *
 * @param routingId 路线主键
 * @param version 当前乐观锁版本
 * @param nodes 节点实体列表
 * @param edges 边视图数据列表
 * @param resourceCodes 资源主键到编码的映射
 */
public record RoutingGraphData(
        UUID routingId,
        Integer version,
        List<RoutingNode> nodes,
        List<RoutingEdgeData> edges,
        Map<UUID, String> resourceCodes) {
}
