package com.hengce.aps.web.controller.masterdata.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 新建工艺路线请求体。
 *
 * <p>契约要求 nodes/edges 必填且 nodes 至少 1 个；本实现额外允许 nodes/edges 为空，
 * 用于「空白版本」；{@code sourceRoutingId} 非空且 nodes/edges 为空时复制来源版本。</p>
 *
 * @param productCode 产品物料编码
 * @param routingVersion 业务版本号
 * @param effectiveDate 生效日期
 * @param expiryDate 失效日期
 * @param nodes 节点
 * @param edges 边
 * @param sourceRoutingId 复制来源路线 UUID，可空
 */
public record RoutingCreateRequest(
        @NotBlank @Size(max = 64) String productCode,
        @NotBlank @Size(max = 32) String routingVersion,
        @NotNull LocalDate effectiveDate,
        LocalDate expiryDate,
        @Valid List<RoutingNodeRequest> nodes,
        @Valid List<RoutingEdgeRequest> edges,
        UUID sourceRoutingId) {
}
