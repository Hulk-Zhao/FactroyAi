package com.hengce.aps.web.controller;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.domain.model.FreezeStatus;
import com.hengce.aps.domain.model.OrderStatus;
import com.hengce.aps.service.masterdata.order.OrderCreateCommand;
import com.hengce.aps.service.masterdata.order.OrderFreezeCommand;
import com.hengce.aps.service.masterdata.order.OrderPriority;
import com.hengce.aps.service.masterdata.order.OrderService;
import com.hengce.aps.service.masterdata.order.OrderSource;
import com.hengce.aps.service.masterdata.order.OrderUpdateCommand;
import com.hengce.aps.service.masterdata.order.OrderView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单管理 API，端点与语义严格对齐 contract-freeze C13。
 *
 * <p>GET 认证读取复用真库查询；POST/PUT/confirm/freeze 为 M1-SE-05 新实现，
 * 订单号寻址遵循 C6，确认校验遵循 C8，筛选语义遵循 C10。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/orders")
@Tag(name = "订单与场景", description = "订单管理与冻结")
public class OrderController {

    private final OrderService orderService;

    /**
     * 构造控制器。
     *
     * @param orderService 订单服务
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 订单列表。
     *
     * @param query 分页参数
     * @param status 订单状态（精确）
     * @param orderNo 订单号关键字（模糊，与 materialCode OR）
     * @param materialCode 产品物料编码关键字（模糊，与 orderNo OR）
     * @param customerId 客户主键（精确）
     * @param dueDateFrom 交期下界（含）
     * @param dueDateTo 交期上界（含）
     * @return 分页订单
     */
    @Operation(summary = "订单列表",
            description = "状态：已实现。C10：orderNo/materialCode 模糊且互为 OR，与 status/customerId/交期区间为 AND。")
    @GetMapping
    public Result<PageResult<OrderView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "DRAFT/CONFIRMED/...") @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "订单号关键字") @RequestParam(required = false) String orderNo,
            @Parameter(description = "产品物料编码关键字") @RequestParam(required = false) String materialCode,
            @Parameter(description = "客户 UUID") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "交期下界 yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
            @Parameter(description = "交期上界 yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo) {
        return Result.success(orderService.list(
                query, status, orderNo, materialCode, customerId, dueDateFrom, dueDateTo));
    }

    /**
     * 订单详情。
     *
     * @param orderNo 订单号（C6 业务编码寻址）
     * @return 订单详情
     */
    @Operation(summary = "订单详情", description = "状态：已实现。路径参数为 order_no（C6）。")
    // 排除通用导出/导入保留段，避免 GET /orders/export 被本方法抢先匹配（C38）
    @GetMapping("/{orderNo:^(?!export$)(?!import$).+}")
    public Result<OrderView> detail(@PathVariable String orderNo) {
        return Result.success(orderService.get(orderNo));
    }

    /**
     * 新建订单，默认 DRAFT。
     *
     * @param request 创建请求
     * @return 创建后的订单
     */
    @Operation(summary = "新建订单", description = "状态：已实现。默认 DRAFT/UNFROZEN；订单号重复返回 409/40900。")
    @RequiresPermission("OM_WRITE")
    @PostMapping
    public Result<OrderView> create(@Valid @RequestBody OrderCreateRequest request) {
        return Result.success(orderService.create(request.toCommand()));
    }

    /**
     * 更新 DRAFT 订单。
     *
     * @param orderNo 订单号
     * @param request 更新请求
     * @return 更新后的订单
     */
    @Operation(summary = "更新订单",
            description = "状态：已实现。仅 DRAFT 可改，orderNo 不可改，version 冲突返回 409/40901，非 DRAFT 返回 422/42200。")
    @RequiresPermission("OM_WRITE")
    @PutMapping("/{orderNo}")
    public Result<OrderView> update(@PathVariable String orderNo,
                                    @Valid @RequestBody OrderUpdateRequest request) {
        return Result.success(orderService.update(orderNo, request.toCommand()));
    }

    /**
     * 确认订单：DRAFT → CONFIRMED，重复确认幂等。
     *
     * @param orderNo 订单号
     * @return 确认后的订单
     */
    @Operation(summary = "确认订单",
            description = "状态：已实现。无 body；重复确认幂等返回 200；C8 校验失败返回 422/42200。")
    @RequiresPermission("OM_WRITE")
    @PutMapping("/{orderNo}/confirm")
    public Result<OrderView> confirm(@PathVariable String orderNo) {
        return Result.success(orderService.confirm(orderNo));
    }

    /**
     * 修改冻结状态；UNFROZEN 即解冻。
     *
     * @param orderNo 订单号
     * @param request 冻结请求
     * @return 更新后的订单
     */
    @Operation(summary = "冻结/解冻订单",
            description = "状态：已实现（C23）。四态 freezeStatus；LOCKED_TIME_WINDOW 需 lockFrom/lockTo，"
                    + "LOCKED_RESOURCE 需 lockedResourceCodes；仅改冻结轴，不改 status；version 冲突返回 409/40901。")
    @RequiresPermission("ORDER_FREEZE")
    @PutMapping("/{orderNo}/freeze")
    public Result<OrderView> freeze(@PathVariable String orderNo,
                                    @Valid @RequestBody OrderFreezeRequest request) {
        return Result.success(orderService.freeze(orderNo, request.toCommand()));
    }

    /**
     * 修改订单优先级。
     *
     * @param orderNo 订单号
     * @param request 优先级请求
     * @return 更新后的订单
     */
    @Operation(summary = "修改订单优先级",
            description = "状态：已实现（C23）。priority 影响排产软约束权重；version 冲突返回 409/40901。")
    @RequiresPermission("OM_WRITE")
    @PutMapping("/{orderNo}/priority")
    public Result<OrderView> priority(@PathVariable String orderNo,
                                      @Valid @RequestBody OrderPriorityRequest request) {
        return Result.success(orderService.changePriority(
                orderNo, request.priority(), request.version()));
    }

    /**
     * 释放订单。
     *
     * @param orderNo 订单号
     * @param request 释放请求，可为空 body
     * @return 释放后的订单
     */
    @Operation(summary = "释放订单",
            description = "状态：已实现（C23）。CONFIRMED/SCHEDULED → RELEASED，并自动把订单下 PLANNED 任务置为 FROZEN；"
                    + "重复释放幂等；其他状态返回 422/42200。")
    @RequiresPermission("OM_WRITE")
    @PutMapping("/{orderNo}/release")
    public Result<OrderView> release(@PathVariable String orderNo,
                                     @RequestBody(required = false) OrderReleaseRequest request) {
        String reason = request == null ? null : request.reason();
        Integer version = request == null ? null : request.version();
        return Result.success(orderService.release(orderNo, reason, version));
    }

    /**
     * 订单开工。
     *
     * @param orderNo 订单号
     * @param request 开工请求，version 必填
     * @return 开工后的订单
     */
    @Operation(summary = "订单开工",
            description = "状态：已实现（C23）。RELEASED → IN_PROGRESS，并自动把订单下 PLANNED 任务置为 FROZEN；"
                    + "重复开工幂等；非 RELEASED 返回 422/42200；version 冲突返回 409/40901。")
    @RequiresPermission("OM_WRITE")
    @PutMapping("/{orderNo}/start")
    public Result<OrderView> start(@PathVariable String orderNo,
                                   @Valid @RequestBody OrderStartRequest request) {
        return Result.success(orderService.start(orderNo, request.version()));
    }

    /**
     * 新建订单请求体，字段与 orders.yaml OrderCreateRequest 对齐。
     *
     * @param orderNo 订单号
     * @param source 来源，默认 MANUAL
     * @param materialCode 产品物料编码
     * @param qty 数量，必须大于 0
     * @param dueDate 交期
     * @param priority 优先级，默认 NORMAL
     * @param customerId 客户主键
     * @param routingId 指定工艺路线主键
     * @param remark 备注
     * @param extAttrs 扩展属性
     */
    public record OrderCreateRequest(
            @NotBlank @Size(max = 64) String orderNo,
            OrderSource source,
            @NotBlank @Size(max = 64) String materialCode,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            @NotNull LocalDate dueDate,
            OrderPriority priority,
            UUID customerId,
            UUID routingId,
            @Size(max = 1000) String remark,
            Map<String, Object> extAttrs) {

        /**
         * 转换为服务层命令。
         *
         * @return 创建命令
         */
        public OrderCreateCommand toCommand() {
            return new OrderCreateCommand(orderNo, source, materialCode, qty, dueDate, priority,
                    customerId, routingId, remark, extAttrs);
        }
    }

    /**
     * 更新订单请求体，字段与 orders.yaml OrderUpdateRequest 对齐。
     *
     * @param materialCode 产品物料编码
     * @param qty 数量，传入时必须大于 0
     * @param dueDate 交期
     * @param priority 优先级
     * @param customerId 客户主键
     * @param routingId 指定工艺路线主键
     * @param remark 备注
     * @param extAttrs 扩展属性
     * @param version 当前版本，必填
     */
    public record OrderUpdateRequest(
            @Size(max = 64) String materialCode,
            @DecimalMin(value = "0", inclusive = false) BigDecimal qty,
            LocalDate dueDate,
            OrderPriority priority,
            UUID customerId,
            UUID routingId,
            @Size(max = 1000) String remark,
            Map<String, Object> extAttrs,
            @NotNull @Min(1) Integer version) {

        /**
         * 转换为服务层命令。
         *
         * @return 更新命令
         */
        public OrderUpdateCommand toCommand() {
            return new OrderUpdateCommand(materialCode, qty, dueDate, priority, customerId, routingId,
                    remark, extAttrs, version);
        }
    }

    /**
     * 冻结请求体，对齐 C23 四态 payload（含锁窗/锁资源）。
     *
     * @param freezeStatus 目标冻结状态，UNFROZEN 即解冻
     * @param lockFrom 锁窗开始（ISO-8601，UTC 偏移），仅 LOCKED_TIME_WINDOW 必填
     * @param lockTo 锁窗结束（ISO-8601，UTC 偏移），仅 LOCKED_TIME_WINDOW 必填
     * @param lockedResourceCodes 锁定资源编码，仅 LOCKED_RESOURCE 必填
     * @param version 当前版本，必填
     */
    public record OrderFreezeRequest(
            @NotNull FreezeStatus freezeStatus,
            OffsetDateTime lockFrom,
            OffsetDateTime lockTo,
            List<@NotBlank @Size(max = 64) String> lockedResourceCodes,
            @NotNull @Min(1) Integer version) {

        /**
         * 转换为服务层命令。
         *
         * @return 冻结命令
         */
        public OrderFreezeCommand toCommand() {
            return new OrderFreezeCommand(freezeStatus, lockFrom, lockTo, lockedResourceCodes, version);
        }
    }

    /**
     * 优先级请求体，对齐 C23。
     *
     * @param priority 目标优先级
     * @param version 当前版本，必填
     */
    public record OrderPriorityRequest(
            @NotNull OrderPriority priority,
            @NotNull @Min(1) Integer version) {
    }

    /**
     * 释放请求体；整体可为空，为空时不做版本校验。
     *
     * @param reason 释放原因，写入审计
     * @param version 当前版本，可选
     */
    public record OrderReleaseRequest(
            @Size(max = 500) String reason,
            @Min(1) Integer version) {
    }

    /**
     * 开工请求体，{@code version} 为契约必填。
     *
     * @param version 当前乐观锁版本
     */
    public record OrderStartRequest(@NotNull @Min(1) Integer version) {
    }
}
