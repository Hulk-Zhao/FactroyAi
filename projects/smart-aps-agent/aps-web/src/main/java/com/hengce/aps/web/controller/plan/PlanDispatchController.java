package com.hengce.aps.web.controller.plan;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.notification.DispatchResultView;
import com.hengce.aps.service.notification.DispatchSummaryView;
import com.hengce.aps.service.notification.PlanDispatchService;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划下发与通知状态接口（C33 §25.5）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/plans")
@Tag(name = "计划下发", description = "计划下发 outbox 与通知状态（C33）")
public class PlanDispatchController {

    private final PlanDispatchService planDispatchService;

    /**
     * 构造控制器。
     *
     * @param planDispatchService 下发服务
     */
    public PlanDispatchController(PlanDispatchService planDispatchService) {
        this.planDispatchService = planDispatchService;
    }

    /**
     * 下发计划。
     *
     * @param id 计划版本主键
     * @param idempotencyKey 幂等键，必填
     * @param request 下发请求
     * @return 下发响应
     */
    @Operation(summary = "计划下发",
            description = "状态：已实现（C33）。仅 PUBLISHED 生效；只写 outbox（QUEUED），不真实发送；"
                    + "重复 Idempotency-Key 返回首次 dispatchId。")
    @RequiresPermission("SCHEDULE_PUBLISH")
    @PostMapping("/{id}/dispatch")
    public Result<DispatchResultView> dispatch(
            @PathVariable UUID id,
            @RequestHeader(ApsConstants.HEADER_IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody DispatchRequest request) {
        List<PlanDispatchService.DispatchTargetCommand> targets = request.targets().stream()
                .map(target -> new PlanDispatchService.DispatchTargetCommand(target.channel(), target.address()))
                .toList();
        return Result.success(planDispatchService.dispatch(id, idempotencyKey, targets, request.message()));
    }

    /**
     * 查询计划的下发批次状态。
     *
     * @param id 计划版本主键
     * @param query 分页参数
     * @return 分页聚合状态
     */
    @Operation(summary = "下发状态",
            description = "状态：已实现（C33）。返回 QUEUED/SENT/PARTIAL/FAILED 聚合；不扩展 PlanDetailView。")
    @RequiresPermission("SCHEDULE_PUBLISH")
    @GetMapping("/{id}/dispatches")
    public Result<PageResult<DispatchSummaryView>> dispatches(
            @PathVariable UUID id,
            @ParameterObject PageQuery query) {
        return Result.success(planDispatchService.listDispatches(id, query));
    }

    /**
     * 下发请求体。
     *
     * @param targets 目标列表，1–50
     * @param message 附加说明，可空
     */
    public record DispatchRequest(
            @NotEmpty @Size(max = 50) @Valid List<DispatchTargetRequest> targets,
            @Size(max = 500) String message) {
    }

    /**
     * 目标。
     *
     * @param channel EMAIL / SMS / FEISHU
     * @param address 目标地址，≤256
     */
    public record DispatchTargetRequest(
            @NotBlank @Size(max = 16) String channel,
            @NotBlank @Size(max = 256) String address) {
    }
}
