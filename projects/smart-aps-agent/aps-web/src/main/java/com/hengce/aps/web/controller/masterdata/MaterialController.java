package com.hengce.aps.web.controller.masterdata;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.service.masterdata.material.MaterialCreateCommand;
import com.hengce.aps.service.masterdata.material.MaterialService;
import com.hengce.aps.service.masterdata.material.MaterialUpdateCommand;
import com.hengce.aps.service.masterdata.material.MaterialView;
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
 * 物料主数据接口，契约见 docs/api/openapi/materials.yaml 与 contract-freeze C6/C8/C10。
 *
 * <p>详情/更新/删除统一使用物料业务编码 {@code {code}}（C6）。</p>
 */
@RestController
@RequestMapping("/api/v1/materials")
@Tag(name = "物料", description = "物料主数据 CRUD（BD-02 已实现）")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    /**
     * 物料列表。
     *
     * @param query 分页参数 page/size/sort
     * @param code 编码关键字（模糊、忽略大小写，与 name OR）
     * @param name 名称关键字（模糊、忽略大小写，与 code OR）
     * @param type 物料类型（精确，AND）
     * @param categoryId 分类主键（精确，AND）
     * @return 分页结果
     */
    @Operation(summary = "物料列表",
            description = "状态：已实现。C10：code/name 模糊且互为 OR，与 type/categoryId 为 AND。")
    @GetMapping
    public Result<PageResult<MaterialView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "物料编码关键字") @RequestParam(required = false) String code,
            @Parameter(description = "物料名称关键字") @RequestParam(required = false) String name,
            @Parameter(description = "FINISHED/SEMI/RAW/AUX/PACK") @RequestParam(required = false) MaterialType type,
            @Parameter(description = "物料分类 UUID") @RequestParam(required = false) UUID categoryId) {
        return Result.success(materialService.list(query, code, name, type, categoryId));
    }

    /**
     * 创建物料。
     *
     * @param request 创建请求
     * @return 创建后的物料
     */
    @Operation(summary = "创建物料", description = "状态：已实现。编码重复返回 409/40901。")
    @RequiresPermission("MD_WRITE")
    @PostMapping
    public Result<MaterialView> create(@Valid @RequestBody MaterialCreateRequest request) {
        return Result.success(materialService.create(request.toCommand()));
    }

    /**
     * 物料详情。
     *
     * @param code 物料业务编码
     * @return 物料详情
     */
    @Operation(summary = "物料详情", description = "状态：已实现。路径参数为 material.code（C6）。")
    // 排除通用导出/导入保留段，避免 GET /materials/export 被本方法抢先匹配（C38）
    @GetMapping("/{code:^(?!export$)(?!import$).+}")
    public Result<MaterialView> get(@PathVariable String code) {
        return Result.success(materialService.get(code));
    }

    /**
     * 更新物料。
     *
     * @param code 物料业务编码
     * @param request 更新请求
     * @return 更新后的物料
     */
    @Operation(summary = "更新物料", description = "状态：已实现。version 必填，冲突返回 409/40901。")
    @RequiresPermission("MD_WRITE")
    @PutMapping("/{code}")
    public Result<MaterialView> update(@PathVariable String code,
                                       @Valid @RequestBody MaterialUpdateRequest request) {
        return Result.success(materialService.update(code, request.toCommand()));
    }

    /**
     * 删除物料（逻辑删除）。
     *
     * @param code 物料业务编码
     * @return 空响应
     */
    @Operation(summary = "删除物料", description = "状态：已实现。被 BOM/订单/工艺路线引用时返回 422/42200。")
    @RequiresPermission("MD_WRITE")
    @DeleteMapping("/{code}")
    public Result<Void> delete(@PathVariable String code) {
        materialService.delete(code);
        return Result.success();
    }

    /**
     * 创建物料请求体，字段与 materials.yaml MaterialCreateRequest 对齐。
     *
     * @param code 物料编码
     * @param name 物料名称
     * @param type 物料类型
     * @param spec 规格型号
     * @param unit 基本单位
     * @param categoryId 分类主键
     * @param safetyStock 安全库存
     * @param moq 最小起订量
     * @param leadTimeDays 采购提前期（天）
     * @param defaultSupplierId 默认供应商主键
     * @param extAttrs 扩展属性
     * @param industryAttrs 行业属性
     */
    public record MaterialCreateRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotNull MaterialType type,
            @Size(max = 128) String spec,
            @NotBlank @Size(max = 16) String unit,
            UUID categoryId,
            @DecimalMin("0.0") BigDecimal safetyStock,
            @DecimalMin("0.0") BigDecimal moq,
            @Min(0) Integer leadTimeDays,
            UUID defaultSupplierId,
            Map<String, Object> extAttrs,
            Map<String, Object> industryAttrs) {

        /**
         * 转为服务层命令。
         *
         * @return 创建命令
         */
        MaterialCreateCommand toCommand() {
            return new MaterialCreateCommand(code, name, type, spec, unit, categoryId,
                    safetyStock, moq, leadTimeDays, defaultSupplierId, extAttrs, industryAttrs);
        }
    }

    /**
     * 更新物料请求体，字段与 materials.yaml MaterialUpdateRequest 对齐。
     *
     * @param code 物料编码（不可修改）
     * @param name 物料名称
     * @param type 物料类型
     * @param spec 规格型号
     * @param unit 基本单位
     * @param categoryId 分类主键
     * @param safetyStock 安全库存
     * @param moq 最小起订量
     * @param leadTimeDays 采购提前期（天）
     * @param defaultSupplierId 默认供应商主键
     * @param extAttrs 扩展属性
     * @param industryAttrs 行业属性
     * @param version 乐观锁版本，必填
     */
    public record MaterialUpdateRequest(
            @Size(max = 64) String code,
            @Size(max = 128) String name,
            MaterialType type,
            @Size(max = 128) String spec,
            @Size(max = 16) String unit,
            UUID categoryId,
            @DecimalMin("0.0") BigDecimal safetyStock,
            @DecimalMin("0.0") BigDecimal moq,
            @Min(0) Integer leadTimeDays,
            UUID defaultSupplierId,
            Map<String, Object> extAttrs,
            Map<String, Object> industryAttrs,
            @NotNull Integer version) {

        /**
         * 转为服务层命令。
         *
         * @return 更新命令
         */
        MaterialUpdateCommand toCommand() {
            return new MaterialUpdateCommand(code, name, type, spec, unit, categoryId,
                    safetyStock, moq, leadTimeDays, defaultSupplierId, extAttrs, industryAttrs, version);
        }
    }
}
