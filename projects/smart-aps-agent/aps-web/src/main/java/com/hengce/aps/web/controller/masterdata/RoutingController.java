package com.hengce.aps.web.controller.masterdata;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import com.hengce.aps.service.process.routing.RoutingCreateCommand;
import com.hengce.aps.service.process.routing.RoutingEdgeCommand;
import com.hengce.aps.service.process.routing.RoutingEdgeData;
import com.hengce.aps.service.process.routing.RoutingGraphCommand;
import com.hengce.aps.service.process.routing.RoutingGraphData;
import com.hengce.aps.service.process.routing.RoutingNodeCommand;
import com.hengce.aps.service.process.routing.RoutingOverrides;
import com.hengce.aps.service.process.routing.RoutingService;
import com.hengce.aps.web.controller.masterdata.dto.RoutingCreateRequest;
import com.hengce.aps.web.controller.masterdata.dto.RoutingEdgeRequest;
import com.hengce.aps.web.controller.masterdata.dto.RoutingEdgeView;
import com.hengce.aps.web.controller.masterdata.dto.RoutingGraphUpdateRequest;
import com.hengce.aps.web.controller.masterdata.dto.RoutingGraphView;
import com.hengce.aps.web.controller.masterdata.dto.RoutingNodeRequest;
import com.hengce.aps.web.controller.masterdata.dto.RoutingNodeView;
import com.hengce.aps.web.controller.masterdata.dto.RoutingView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工艺路线 API。
 *
 * <p>契约 C15 口径：路径参数与资源引用均为 UUID；边对外按 seqNo 表达；
 * 图保存 body 的 {@code version} 为乐观锁整数；同一产品仅一个 ACTIVE 路线。</p>
 *
 * <p>说明：本控制器提供契约中的 {@code GET/POST /routings} 与 {@code GET/PUT /routings/{id}/graph}，
 * 另按 M1-BD-07 范围补充 {@code GET /routings/{id}}、{@code POST /routings/{id}/activate}、
 * {@code DELETE /routings/{id}}；后者需补契约变更单。若契约占位控制器尚未退役，
 * 同路径会与占位实现产生 Spring 重复映射，需由修障流移除占位后启动。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/routings")
@Tag(name = "工艺路线", description = "工艺路线 DAG：节点资源 UUID、边 seqNo、乐观锁版本（C15）")
public class RoutingController {

    private final RoutingService routingService;

    /**
     * 构造控制器。
     *
     * @param routingService 工艺路线服务
     */
    public RoutingController(RoutingService routingService) {
        this.routingService = routingService;
    }

    /**
     * 工艺路线列表。
     *
     * @param query 分页与排序参数
     * @param productCode 产品物料编码，精确匹配
     * @param version 业务版本号 routing_version
     * @param status 状态 DRAFT/ACTIVE/INACTIVE
     * @return 分页结果
     */
    @Operation(summary = "工艺路线列表", description = "已实现。productCode/version/status 之间为 AND。")
    @GetMapping
    public Result<PageResult<RoutingView>> list(@ParameterObject PageQuery query,
                                                @RequestParam(required = false) String productCode,
                                                @RequestParam(required = false) String version,
                                                @RequestParam(required = false) String status) {
        PageResult<Routing> page = routingService.list(query, productCode, version, status);
        List<RoutingView> items = page.items().stream()
                .map(routing -> toRoutingView(routing, null))
                .toList();
        return Result.success(new PageResult<>(items, page.page(), page.size(),
                page.total(), page.totalPages()));
    }

    /**
     * 工艺路线详情。
     *
     * @param id 路线 UUID
     * @return 路线（含完整节点/边）
     */
    @Operation(summary = "工艺路线详情", description = "已实现。路径参数为 routing UUID；不存在返回 40400。")
    // 排除通用导出/导入保留段，避免 GET /routings/export 被本方法抢先匹配（C38）
    @GetMapping("/{id:^(?!export$)(?!import$).+}")
    public Result<RoutingView> detail(@PathVariable UUID id) {
        Routing routing = routingService.get(id);
        return Result.success(toRoutingView(routing, routingService.getGraph(id)));
    }

    /**
     * 新建工艺路线版本。
     *
     * @param request 新建请求
     * @return 新建后的路线
     */
    @Operation(summary = "新建工艺路线", description = "已实现。默认 DRAFT；支持空白图或通过 sourceRoutingId 复制现有版本。")
    @RequiresPermission("MD_WRITE")
    @PostMapping
    public Result<RoutingView> create(@Valid @RequestBody RoutingCreateRequest request) {
        Routing routing = routingService.create(toCommand(request));
        return Result.success(toRoutingView(routing,
                routingService.getGraph((UUID) routing.getId())));
    }

    /**
     * 读取工艺路线 DAG。
     *
     * @param id 路线 UUID
     * @return 图
     */
    @Operation(summary = "工艺路线 DAG", description = "已实现。边由 node UUID 映射为 fromSeqNo/toSeqNo。")
    @GetMapping("/{id}/graph")
    public Result<RoutingGraphView> graph(@PathVariable UUID id) {
        return Result.success(toGraphView(routingService.getGraph(id)));
    }

    /**
     * 整体替换工艺路线 DAG。
     *
     * @param id 路线 UUID
     * @param request 节点/边与乐观锁版本
     * @return 保存后的图
     */
    @Operation(summary = "保存工艺路线 DAG", description = "已实现。整体替换 nodes/edges；version 不一致返回 40901；图非法返回 42200。")
    @RequiresPermission("MD_WRITE")
    @PutMapping("/{id}/graph")
    public Result<RoutingGraphView> updateGraph(@PathVariable UUID id,
                                                @Valid @RequestBody RoutingGraphUpdateRequest request) {
        RoutingGraphCommand command = new RoutingGraphCommand(
                request.nodes() == null ? null : request.nodes().stream()
                        .map(RoutingController::toNodeCommand).toList(),
                request.edges() == null ? null : request.edges().stream()
                        .map(RoutingController::toEdgeCommand).toList(),
                request.version());
        return Result.success(toGraphView(routingService.saveGraph(id, command)));
    }

    /**
     * 激活工艺路线。
     *
     * @param id 路线 UUID
     * @return 激活后的路线
     */
    @Operation(summary = "激活工艺路线", description = "已实现（契约外补充）。同产品其他 ACTIVE 自动收敛；并发冲突返回 40901。")
    @RequiresPermission("MD_WRITE")
    @PostMapping("/{id}/activate")
    public Result<RoutingView> activate(@PathVariable UUID id) {
        Routing routing = routingService.activate(id);
        return Result.success(toRoutingView(routing, routingService.getGraph(id)));
    }

    /**
     * 删除工艺路线。
     *
     * @param id 路线 UUID
     * @return 空数据成功响应
     */
    @Operation(summary = "删除工艺路线", description = "已实现（契约外补充）。逻辑删除；被 orders/task 引用返回 42200。")
    @RequiresPermission("MD_WRITE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        routingService.delete(id);
        return Result.success();
    }

    private static RoutingCreateCommand toCommand(RoutingCreateRequest request) {
        return new RoutingCreateCommand(
                request.productCode(),
                request.routingVersion(),
                request.effectiveDate(),
                request.expiryDate(),
                request.nodes() == null ? null : request.nodes().stream()
                        .map(RoutingController::toNodeCommand).toList(),
                request.edges() == null ? null : request.edges().stream()
                        .map(RoutingController::toEdgeCommand).toList(),
                request.sourceRoutingId());
    }

    private static RoutingNodeCommand toNodeCommand(RoutingNodeRequest request) {
        return new RoutingNodeCommand(
                request.seqNo(),
                request.operationTemplateId(),
                request.operationCode(),
                request.operationName(),
                request.standardSeconds(),
                request.setupSeconds(),
                request.primaryResourceMode(),
                request.primaryResourceId(),
                request.resourceCode(),
                request.secondaryResourceIds(),
                request.optional());
    }

    private static RoutingEdgeCommand toEdgeCommand(RoutingEdgeRequest request) {
        return new RoutingEdgeCommand(
                request.fromSeqNo(),
                request.toSeqNo(),
                request.dependType(),
                request.lagMinutes(),
                request.from(),
                request.to());
    }

    private static RoutingView toRoutingView(Routing routing, RoutingGraphData graph) {
        List<RoutingNodeView> nodes = List.of();
        List<RoutingEdgeView> edges = List.of();
        if (graph != null) {
            nodes = graph.nodes().stream()
                    .map(node -> toNodeView(node, graph.resourceCodes()))
                    .toList();
            edges = graph.edges().stream().map(RoutingController::toEdgeView).toList();
        }
        return new RoutingView(
                (UUID) routing.getId(),
                routing.getProductCode(),
                routing.getRoutingVersion(),
                routing.getStatus(),
                routing.getEffectiveDate(),
                routing.getExpiryDate(),
                nodes,
                edges,
                routing.getVersion());
    }

    private static RoutingGraphView toGraphView(RoutingGraphData graph) {
        List<RoutingNodeView> nodes = graph.nodes().stream()
                .map(node -> toNodeView(node, graph.resourceCodes()))
                .toList();
        List<RoutingEdgeView> edges = graph.edges().stream()
                .map(RoutingController::toEdgeView)
                .toList();
        return new RoutingGraphView(graph.routingId(), nodes, edges);
    }

    private static RoutingNodeView toNodeView(RoutingNode node, Map<UUID, String> resourceCodes) {
        // 响应必须回显模式：primaryResourceId=null 时 INHERIT 与 FLOAT 只能靠它区分。
        String primaryResourceMode = RoutingOverrides.primaryResourceMode(node.getOverrides());
        UUID primaryResourceId = RoutingOverrides.primaryResourceId(node.getOverrides());
        String primaryResourceCode = primaryResourceId == null ? null : resourceCodes.get(primaryResourceId);
        List<UUID> secondaryResourceIds = RoutingOverrides.secondaryResourceIds(node.getOverrides());
        return new RoutingNodeView(
                node.getSeqNo(),
                node.getOperationTemplateId(),
                node.getOperationCode(),
                node.getOperationName(),
                node.getStandardSeconds(),
                node.getSetupSeconds(),
                primaryResourceMode,
                primaryResourceId,
                primaryResourceCode,
                secondaryResourceIds,
                Boolean.TRUE.equals(node.getIsOptional()),
                node.getSeqNo(),
                primaryResourceCode);
    }

    private static RoutingEdgeView toEdgeView(RoutingEdgeData edge) {
        return new RoutingEdgeView(
                edge.fromNodeKey(),
                edge.toNodeKey(),
                edge.fromSeqNo(),
                edge.toSeqNo(),
                edge.dependType(),
                edge.lagMinutes(),
                edge.lagMinutes());
    }
}
