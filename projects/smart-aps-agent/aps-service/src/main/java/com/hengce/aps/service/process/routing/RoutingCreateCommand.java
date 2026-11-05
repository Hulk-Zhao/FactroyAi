package com.hengce.aps.service.process.routing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 新建工艺路线命令。
 *
 * <p>支持三种新建方式：</p>
 * <ul>
 *   <li>显式图：传 nodes/edges；</li>
 *   <li>复制现有版本：{@code sourceRoutingId} 非空且 nodes 为空，服务层复制来源图；</li>
 *   <li>空白版本：nodes/edges 均为空，创建空 DAG 草稿。</li>
 * </ul>
 *
 * @param productCode 产品物料编码
 * @param routingVersion 业务版本号
 * @param effectiveDate 生效日期
 * @param expiryDate 失效日期，可空
 * @param nodes 节点列表
 * @param edges 边列表
 * @param sourceRoutingId 复制来源路线 UUID，可空
 */
public record RoutingCreateCommand(
        String productCode,
        String routingVersion,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        List<RoutingNodeCommand> nodes,
        List<RoutingEdgeCommand> edges,
        UUID sourceRoutingId) {
}
