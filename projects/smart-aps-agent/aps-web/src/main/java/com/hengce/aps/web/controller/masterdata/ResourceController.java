package com.hengce.aps.web.controller.masterdata;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.service.masterdata.resource.ResourceCreateCommand;
import com.hengce.aps.service.masterdata.resource.ResourceService;
import com.hengce.aps.service.masterdata.resource.ResourceUpdateCommand;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
 * 资源主数据接口，契约见 docs/api/openapi/materials.yaml、contract-freeze C6/C10/C14。
 *
 * <p>资源对外用业务编码 {@code {code}} 寻址；响应带只读 {@code id}（UUID）供日历绑定，
 * 不引入 {@code status} 字段，停用通过 {@code DELETE /resources/{code}} 软删除表达。</p>
 *
 * <p>注意：{@code controller/contract/MaterialsContractController} 仍保留
 * {@code GET/POST /api/v1/resources} 占位方法，与本控制器产生启动期 Ambiguous mapping。
 * 按要求不做 {@code produces} 规避，冲突由修障流统一退役占位后消除。</p>
 */
@RestController
@RequestMapping("/api/v1/resources")
@Tag(name = "资源", description = "资源主数据 CRUD（BD-04 已实现）")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    /**
     * 资源列表。
     *
     * @param query 分页参数 page/size/sort
     * @param code 编码关键字（模糊、忽略大小写，与 name OR）
     * @param name 名称关键字（模糊、忽略大小写，与 code OR）
     * @param type 资源类型（精确，AND）
     * @param groupId 资源组 UUID（精确，AND）
     * @return 分页结果
     */
    @Operation(summary = "资源列表",
            description = "状态：已实现。C10：code/name 模糊且互为 OR，与 type/groupId 为 AND；无 status 参数。")
    @GetMapping
    public Result<PageResult<ResourceView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "资源编码关键字") @RequestParam(required = false) String code,
            @Parameter(description = "资源名称关键字") @RequestParam(required = false) String name,
            @Parameter(description = "PRIMARY/SECONDARY/HUMAN/TOOL") @RequestParam(required = false) ResourceType type,
            @Parameter(description = "资源组 UUID") @RequestParam(required = false) UUID groupId) {
        return Result.success(resourceService.list(query, code, name, type, groupId));
    }

    /**
     * 创建资源。
     *
     * @param request 创建请求
     * @return 创建后的资源
     */
    @Operation(summary = "创建资源", description = "状态：已实现。编码重复返回 409/40901，产能非法返回 422/42200。")
    @RequiresPermission("MD_WRITE")
    @PostMapping
    public Result<ResourceView> create(@Valid @RequestBody ResourceCreateRequest request) {
        return Result.success(resourceService.create(request.toCommand()));
    }

    /**
     * 资源详情。
     *
     * @param code 资源业务编码
     * @return 资源详情
     */
    @Operation(summary = "资源详情", description = "状态：已实现。路径参数为 resource.code（C6），响应带只读 id。")
    // 排除通用导出/导入保留段，避免 GET /resources/export 被本方法抢先匹配（C38）
    @GetMapping("/{code:^(?!export$)(?!import$).+}")
    public Result<ResourceView> get(@PathVariable String code) {
        return Result.success(resourceService.get(code));
    }

    /**
     * 更新资源。
     *
     * @param code 资源业务编码
     * @param request 更新请求
     * @return 更新后的资源
     */
    @Operation(summary = "更新资源", description = "状态：已实现。code 不可修改，version 必填，冲突返回 409/40901。")
    @RequiresPermission("MD_WRITE")
    @PutMapping("/{code}")
    public Result<ResourceView> update(@PathVariable String code,
                                       @Valid @RequestBody ResourceUpdateRequest request) {
        return Result.success(resourceService.update(code, request.toCommand()));
    }

    /**
     * 停用资源（软删除）。
     *
     * @param code 资源业务编码
     * @return 空响应
     */
    @Operation(summary = "停用资源", description = "状态：已实现。deleted=true 软删除；被日历/任务引用返回 422/42200；重复调用幂等。")
    @RequiresPermission("MD_WRITE")
    @DeleteMapping("/{code}")
    public Result<Void> delete(@PathVariable String code) {
        resourceService.delete(code);
        return Result.success();
    }

    /**
     * 创建资源请求体，字段与 materials.yaml ResourceCreateRequest 对齐。
     *
     * @param code 资源编码
     * @param name 资源名称
     * @param type 资源类型
     * @param groupId 资源组主键
     * @param capacityMode 产能模式
     * @param capacityValue 产能值
     * @param batchCapacity 批量
     * @param efficiency 效率系数
     * @param calendarId 绑定日历主键
     * @param setupSeconds 换型/准备工时（秒）
     * @param teardownSeconds 拆卸工时（秒）
     * @param industryAttrs 行业属性
     */
    public record ResourceCreateRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 256) String name,
            @NotNull ResourceType type,
            UUID groupId,
            @NotNull CapacityMode capacityMode,
            BigDecimal capacityValue,
            Integer batchCapacity,
            BigDecimal efficiency,
            UUID calendarId,
            Integer setupSeconds,
            Integer teardownSeconds,
            Map<String, Object> industryAttrs) {

        /**
         * 转为服务层命令。
         *
         * @return 创建命令
         */
        ResourceCreateCommand toCommand() {
            return new ResourceCreateCommand(code, name, type, groupId, capacityMode,
                    capacityValue, batchCapacity, efficiency, calendarId,
                    setupSeconds, teardownSeconds, industryAttrs);
        }
    }

    /**
     * 更新资源请求体，字段与 materials.yaml ResourceUpdateRequest 对齐。
     *
     * @param name 资源名称
     * @param type 资源类型
     * @param groupId 资源组主键
     * @param capacityMode 产能模式
     * @param capacityValue 产能值
     * @param batchCapacity 批量
     * @param efficiency 效率系数
     * @param calendarId 绑定日历主键
     * @param setupSeconds 换型/准备工时（秒）
     * @param teardownSeconds 拆卸工时（秒）
     * @param industryAttrs 行业属性
     * @param version 乐观锁版本，必填
     */
    public record ResourceUpdateRequest(
            @Size(max = 256) String name,
            ResourceType type,
            UUID groupId,
            CapacityMode capacityMode,
            BigDecimal capacityValue,
            Integer batchCapacity,
            BigDecimal efficiency,
            UUID calendarId,
            Integer setupSeconds,
            Integer teardownSeconds,
            Map<String, Object> industryAttrs,
            @NotNull Integer version) {

        /**
         * 转为服务层命令。
         *
         * @return 更新命令
         */
        ResourceUpdateCommand toCommand() {
            return new ResourceUpdateCommand(name, type, groupId, capacityMode, capacityValue,
                    batchCapacity, efficiency, calendarId, setupSeconds, teardownSeconds,
                    industryAttrs, version);
        }
    }
}
