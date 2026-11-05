package com.hengce.aps.web.controller.plan;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.plan.PlanActionView;
import com.hengce.aps.service.plan.PlanDetailView;
import com.hengce.aps.service.plan.PlanVersionService;
import com.hengce.aps.service.plan.PlanVersionSummaryView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划版本接口（C24）。
 *
 * <p>{@code {id}} 均为 {@code plan_version.id}，即对外 {@code planId}。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/plans")
@Tag(name = "计划版本", description = "计划版本发布、回滚与详情（C24）")
public class PlanController {

    private final PlanVersionService planVersionService;

    /**
     * 构造控制器。
     *
     * @param planVersionService 计划版本服务
     */
    public PlanController(PlanVersionService planVersionService) {
        this.planVersionService = planVersionService;
    }

    /**
     * 计划版本列表。
     *
     * @param query 分页参数
     * @param sandboxId 场景标识，必填
     * @return 分页版本摘要，按 publishedAt 倒序、id 倒序
     */
    @Operation(summary = "计划版本列表",
            description = "状态：已实现（C24）。sandboxId 必填；按 published_at DESC、id DESC 稳定排序；不含任务明细。")
    @GetMapping
    public Result<PageResult<PlanVersionSummaryView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "场景 UUID，必填") @RequestParam UUID sandboxId) {
        return Result.success(planVersionService.list(query, sandboxId));
    }

    /**
     * 发布计划版本。
     *
     * @param id 计划版本主键
     * @param request 发布请求，可为空
     * @return 计划动作响应
     */
    @Operation(summary = "发布计划版本",
            description = "状态：已实现（C24）。DRAFT/REVIEW → PUBLISHED，固化不可变快照并回填 task.plan_version_id；"
                    + "重复发布幂等返回 200。")
    @RequiresPermission("SCHEDULE_PUBLISH")
    @PostMapping("/{id}/publish")
    public Result<PlanActionView> publish(@PathVariable UUID id,
                                          @Valid @RequestBody(required = false) PlanPublishRequest request) {
        String reason = request == null ? null : request.reason();
        String approver = request == null ? null : request.approver();
        return Result.success(planVersionService.publish(id, reason, approver));
    }

    /**
     * 回滚到历史版本。
     *
     * @param id 当前计划版本主键
     * @param request 回滚请求
     * @return 计划动作响应
     */
    @Operation(summary = "回滚计划版本",
            description = "状态：已实现（C24）。新建 PUBLISHED 版本并复制目标快照；场景内存在 IN_PROGRESS/COMPLETED 任务时"
                    + "返回 409/40903。")
    @RequiresPermission("SCHEDULE_PUBLISH")
    @PostMapping("/{id}/rollback")
    public Result<PlanActionView> rollback(@PathVariable UUID id,
                                           @Valid @RequestBody PlanRollbackRequest request) {
        return Result.success(planVersionService.rollback(id, request.targetVersionNo(), request.reason()));
    }

    /**
     * 查询计划版本详情。
     *
     * @param id 计划版本主键
     * @return 版本元信息与快照任务
     */
    @Operation(summary = "查询计划版本详情",
            description = "状态：已实现（C24）。返回版本元信息、任务数与不可变快照任务列表。")
    @GetMapping("/{id}")
    public Result<PlanDetailView> detail(@PathVariable UUID id) {
        return Result.success(planVersionService.detail(id));
    }

    /**
     * 发布请求体，对齐 gantt.yaml PlanPublishRequest。
     *
     * @param reason 发布原因，可为空
     * @param approver 审批人，可为空；为空时落 NULL
     */
    public record PlanPublishRequest(
            @Size(max = 500) String reason,
            @Size(max = 64) String approver) {
    }

    /**
     * 回滚请求体，对齐 gantt.yaml PlanRollbackRequest。
     *
     * @param targetVersionNo 目标版本号，必填
     * @param reason 回滚原因，可为空
     */
    public record PlanRollbackRequest(
            @NotNull @Min(1) Integer targetVersionNo,
            @Size(max = 500) String reason) {
    }
}
