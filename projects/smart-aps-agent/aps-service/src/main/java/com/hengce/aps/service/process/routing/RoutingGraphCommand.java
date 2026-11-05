package com.hengce.aps.service.process.routing;

import java.util.List;

/**
 * 图整体替换命令。
 *
 * @param nodes 新节点列表
 * @param edges 新边列表
 * @param version 乐观锁版本，必须与当前 routing.version 一致
 */
public record RoutingGraphCommand(
        List<RoutingNodeCommand> nodes,
        List<RoutingEdgeCommand> edges,
        Integer version) {
}
