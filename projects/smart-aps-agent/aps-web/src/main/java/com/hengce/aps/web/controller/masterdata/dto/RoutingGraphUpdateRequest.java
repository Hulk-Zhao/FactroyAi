package com.hengce.aps.web.controller.masterdata.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 图整体替换请求体。
 *
 * <p>{@code version} 为乐观锁整数，与 routing.version 不一致返回 40901。</p>
 *
 * @param nodes 节点列表，至少 1 个
 * @param edges 边列表
 * @param version 乐观锁版本
 */
public record RoutingGraphUpdateRequest(
        @NotEmpty @Valid List<RoutingNodeRequest> nodes,
        @Valid List<RoutingEdgeRequest> edges,
        @NotNull @Min(1) Integer version) {
}
