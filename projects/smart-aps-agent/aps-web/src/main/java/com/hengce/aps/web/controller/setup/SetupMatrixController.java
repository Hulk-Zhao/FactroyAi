package com.hengce.aps.web.controller.setup;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.setup.SetupMatrixCommand;
import com.hengce.aps.service.setup.SetupMatrixService;
import com.hengce.aps.service.setup.SetupMatrixView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.util.UUID;

/**
 * 换型矩阵接口（V05-08 / C44），契约见 docs/api/openapi/materials.yaml。
 *
 * <p>FROM/TO 维度按产品（PRODUCT/MATERIAL_CODE）或程序（PROGRAM/OPERATION_CODE）成组；
 * 生效范围为租户/资源组/资源三级。Excel 导入复用 {@code /api/v1/import/setup_matrix}。</p>
 */
@RestController
@RequestMapping("/api/v1/setup-matrix")
@Tag(name = "换型矩阵", description = "FROM/TO 产品-程序换型时间矩阵（V05-08 已实现）")
public class SetupMatrixController {

    private final SetupMatrixService setupMatrixService;

    /**
     * 构造控制器。
     *
     * @param setupMatrixService 换型矩阵服务
     */
    public SetupMatrixController(SetupMatrixService setupMatrixService) {
        this.setupMatrixService = setupMatrixService;
    }

    /**
     * 换型矩阵列表。
     *
     * @param query 分页参数
     * @param keyType 维度过滤
     * @param scopeType 范围过滤
     * @param resourceId 资源过滤
     * @param resourceGroupId 资源组过滤
     * @param fromKey FROM 键精确过滤
     * @param toKey TO 键精确过滤
     * @return 分页结果
     */
    @Operation(summary = "换型矩阵列表", description = "状态：已实现。C44：固定按 keyType、scopeType、created_at 排序。")
    @GetMapping
    public Result<PageResult<SetupMatrixView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "PRODUCT/PROGRAM 或 MATERIAL_CODE/OPERATION_CODE")
            @RequestParam(required = false) String keyType,
            @Parameter(description = "TENANT/RESOURCE_GROUP/RESOURCE")
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) UUID resourceGroupId,
            @RequestParam(required = false) String fromKey,
            @RequestParam(required = false) String toKey) {
        return Result.success(setupMatrixService.list(query, keyType, scopeType,
                resourceId, resourceGroupId, fromKey, toKey));
    }

    /**
     * 矩阵详情。
     *
     * @param id 主键
     * @return 详情
     */
    @Operation(summary = "换型矩阵详情", description = "状态：已实现。")
    @GetMapping("/{id}")
    public Result<SetupMatrixView> get(@PathVariable UUID id) {
        return Result.success(setupMatrixService.get(id));
    }

    /**
     * 创建矩阵条目。
     *
     * @param request 创建请求
     * @return 创建后的条目
     */
    @Operation(summary = "创建换型矩阵条目",
            description = "状态：已实现。业务键重复返回 409/40901；资源/资源组不存在返回 422/42200。")
    @RequiresPermission("MD_WRITE")
    @PostMapping
    public Result<SetupMatrixView> create(@Valid @RequestBody SetupMatrixRequest request) {
        return Result.success(setupMatrixService.create(request.toCommand(null)));
    }

    /**
     * 更新矩阵条目。
     *
     * @param id 主键
     * @param request 更新请求
     * @return 更新后的条目
     */
    @Operation(summary = "更新换型矩阵条目", description = "状态：已实现。version 必填，冲突返回 409/40901。")
    @RequiresPermission("MD_WRITE")
    @PutMapping("/{id}")
    public Result<SetupMatrixView> update(@PathVariable UUID id,
                                          @Valid @RequestBody SetupMatrixRequest request) {
        return Result.success(setupMatrixService.update(id, request.toCommand(request.version())));
    }

    /**
     * 删除矩阵条目（软删除）。
     *
     * @param id 主键
     * @return 空响应
     */
    @Operation(summary = "删除换型矩阵条目", description = "状态：已实现。软删除，不存在返回 404/40400。")
    @RequiresPermission("MD_WRITE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        setupMatrixService.delete(id);
        return Result.success();
    }

    /**
     * 换型矩阵创建/更新请求体。
     *
     * @param keyType 维度：PRODUCT/PROGRAM（或 MATERIAL_CODE/OPERATION_CODE）
     * @param fromKey 前序维度键，空表示通配默认
     * @param toKey 当前维度键，空表示通配默认
     * @param resourceId 资源主键
     * @param resourceGroupId 资源组主键
     * @param setupSeconds 默认换型秒数
     * @param sameSeriesSeconds 同系列特例秒数
     * @param crossSeriesSeconds 跨系列特例秒数
     * @param remark 备注
     * @param version 乐观锁版本，更新时必填
     */
    public record SetupMatrixRequest(
            @NotBlank @Size(max = 32) String keyType,
            @Size(max = 64) String fromKey,
            @Size(max = 64) String toKey,
            UUID resourceId,
            UUID resourceGroupId,
            @NotNull @Min(0) Integer setupSeconds,
            @Min(0) Integer sameSeriesSeconds,
            @Min(0) Integer crossSeriesSeconds,
            @Size(max = 255) String remark,
            Integer version) {

        /**
         * 转为服务层命令。
         *
         * @param version 更新时的乐观锁版本
         * @return 命令
         */
        SetupMatrixCommand toCommand(Integer version) {
            return new SetupMatrixCommand(keyType, fromKey, toKey, resourceId, resourceGroupId,
                    setupSeconds, sameSeriesSeconds, crossSeriesSeconds, remark, version);
        }
    }
}
