package com.hengce.aps.web.controller.sandbox;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.sandbox.SandboxApplyResultView;
import com.hengce.aps.service.sandbox.SandboxCompareView;
import com.hengce.aps.service.sandbox.SandboxService;
import com.hengce.aps.service.sandbox.SandboxSummaryView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景生命周期接口（C32 §25.3）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/sandboxes")
@Tag(name = "场景沙箱", description = "场景列表/创建、apply 提升 BASELINE 与差异对比（C32）")
public class SandboxController {

    private final SandboxService sandboxService;

    /**
     * 构造控制器。
     *
     * @param sandboxService 场景服务
     */
    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    /**
     * 场景列表。
     *
     * @param query 分页参数
     * @param type 类型过滤，可空；缺省排除 ARCHIVED
     * @return 分页场景摘要
     */
    @Operation(summary = "场景列表",
            description = "状态：已实现（C32）。默认按 createdAt DESC；缺省不返回 ARCHIVED；sort 仅允许 createdAt。")
    @RequiresPermission("SS_READ")
    @GetMapping
    public Result<PageResult<SandboxSummaryView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "BASELINE / WHAT_IF / ARCHIVED") @RequestParam(required = false) String type) {
        return Result.success(sandboxService.list(query, type));
    }

    /**
     * 创建 WHAT_IF 场景。
     *
     * @param request 创建请求
     * @return 场景摘要
     */
    @Operation(summary = "创建场景",
            description = "状态：已实现（C32）。只允许 WHAT_IF；父场景缺省为当前 BASELINE，创建即深拷贝 snapshot。")
    @RequiresPermission("SS_WRITE")
    @PostMapping
    public Result<SandboxSummaryView> create(@Valid @RequestBody SandboxCreateRequest request) {
        return Result.success(sandboxService.create(request.name(), request.type(),
                request.parentSandboxId(), request.expiresAt()));
    }

    /**
     * 应用 WHAT_IF 场景为新的 BASELINE。
     *
     * @param id 场景主键
     * @param idempotencyKey 幂等键，必填
     * @param request 应用请求，可为空
     * @return 场景摘要 + 计划动作
     */
    @Operation(summary = "应用场景",
            description = "状态：已实现（C32）。单事务内发布新计划版本、归档旧 BASELINE、目标提升 BASELINE；"
                    + "Idempotency-Key 必填。")
    @RequiresPermission("SCHEDULE_PUBLISH")
    @PostMapping("/{id}/apply")
    public Result<SandboxApplyResultView> apply(
            @PathVariable UUID id,
            @RequestHeader(ApsConstants.HEADER_IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody(required = false) SandboxApplyRequest request) {
        String reason = request == null ? null : request.reason();
        return Result.success(sandboxService.apply(id, reason));
    }

    /**
     * 场景差异对比。
     *
     * @param id 被比较方场景
     * @param targetId 基线方场景
     * @return 差异结果，方向 {@code id - targetId}
     */
    @Operation(summary = "场景对比",
            description = "状态：已实现（C32）。方向固定 id - targetId；返回 added/removed/movedTaskCount、"
                    + "changeType 与 truncated。")
    @RequiresPermission("SS_READ")
    @GetMapping("/{id}/compare")
    public Result<SandboxCompareView> compare(
            @PathVariable UUID id,
            @Parameter(description = "基线方场景 UUID，必填") @RequestParam UUID targetId) {
        return Result.success(sandboxService.compare(id, targetId));
    }

    /**
     * 创建场景请求体。
     *
     * @param name 名称，1–128
     * @param type 类型，缺省 WHAT_IF
     * @param parentSandboxId 父场景，可空
     * @param expiresAt 过期时间，可空
     */
    public record SandboxCreateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 16) String type,
            UUID parentSandboxId,
            OffsetDateTime expiresAt) {
    }

    /**
     * 应用场景请求体。
     *
     * @param reason 可选说明
     */
    public record SandboxApplyRequest(@Size(max = 500) String reason) {
    }
}
