package com.hengce.aps.web.controller.masterdata.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 工艺路线响应体，字段对齐契约 Routing。
 *
 * @param id 路线 UUID
 * @param productCode 产品物料编码
 * @param routingVersion 业务版本号
 * @param status 状态
 * @param effectiveDate 生效日期
 * @param expiryDate 失效日期
 * @param nodes 节点
 * @param edges 边
 * @param version 乐观锁版本
 */
public record RoutingView(
        UUID id,
        String productCode,
        String routingVersion,
        String status,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        List<RoutingNodeView> nodes,
        List<RoutingEdgeView> edges,
        Integer version) {
}
