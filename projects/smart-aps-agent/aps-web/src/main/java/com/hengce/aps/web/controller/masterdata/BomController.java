package com.hengce.aps.web.controller.masterdata;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.masterdata.bom.BomCreateCommand;
import com.hengce.aps.service.masterdata.bom.BomItemCommand;
import com.hengce.aps.service.masterdata.bom.BomService;
import com.hengce.aps.service.masterdata.bom.BomStatus;
import com.hengce.aps.service.masterdata.bom.BomTreeNodeView;
import com.hengce.aps.service.masterdata.bom.BomView;
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
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOM 接口，契约见 docs/api/openapi/materials.yaml 与 contract-freeze C6/C8/C10。
 *
 * <p>全部资源以 UUID 寻址（C6）；{@code parentMaterialCode}/{@code status} 为精确匹配且 AND（C10）。
 * 路由与尚未退役的 {@code MaterialsContractController} 占位同路径，按任务卡要求不使用
 * {@code produces} 绕过，由修障流统一退役占位后生效。</p>
 */
@RestController
@RequestMapping("/api/v1/boms")
@Tag(name = "BOM", description = "BOM 主数据与多级树（BD-03 已实现）")
public class BomController {

    private final BomService bomService;

    public BomController(BomService bomService) {
        this.bomService = bomService;
    }

    /**
     * BOM 列表。
     *
     * @param query 分页参数 page/size/sort
     * @param parentMaterialCode 父件物料编码（精确，AND）
     * @param status 状态（精确，AND）
     * @return 分页结果，每行包含 items
     */
    @Operation(summary = "BOM 列表", description = "状态：已实现。parentMaterialCode/status 精确匹配且 AND（C10）。")
    @GetMapping
    public Result<PageResult<BomView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "父件物料编码（精确）") @RequestParam(required = false) String parentMaterialCode,
            @Parameter(description = "DRAFT/ACTIVE/INACTIVE") @RequestParam(required = false) BomStatus status) {
        return Result.success(bomService.list(query, parentMaterialCode, status));
    }

    /**
     * 新建 BOM，默认 DRAFT。
     *
     * @param request 新建请求
     * @return 新建后的 BOM
     */
    @Operation(summary = "新建 BOM", description = "状态：已实现。同父件同版本返回 409/40900，物料缺失返回 422/42200。")
    @RequiresPermission("MD_WRITE")
    @PostMapping
    public Result<BomView> create(@Valid @RequestBody BomCreateRequest request) {
        return Result.success(bomService.create(request.toCommand()));
    }

    /**
     * BOM 详情，items 的稳定来源。
     *
     * @param bomId BOM 主键
     * @return BOM 详情
     */
    @Operation(summary = "BOM 详情", description = "状态：已实现。UUID 寻址（C6）。")
    // 排除通用导出/导入保留段，避免 GET /boms/export 被本方法抢先匹配（C38）
    @GetMapping("/{bomId:^(?!export$)(?!import$).+}")
    public Result<BomView> get(@PathVariable UUID bomId) {
        return Result.success(bomService.get(bomId));
    }

    /**
     * 多级 BOM 树，默认最大 5 层。
     *
     * @param id 根 BOM 主键
     * @return 树根节点
     */
    @Operation(summary = "BOM 多级树", description = "状态：已实现。检测到循环引用返回 422/42200 并给出环路径。")
    @GetMapping("/{id}/tree")
    public Result<BomTreeNodeView> tree(@PathVariable UUID id) {
        return Result.success(bomService.tree(id));
    }

    /**
     * 激活 BOM。
     *
     * @param id BOM 主键
     * @return 激活后的 BOM
     */
    @Operation(summary = "激活 BOM", description = "状态：已实现。同父件双 ACTIVE 冲突返回 409/40901，无明细返回 422/42200。")
    @RequiresPermission("MD_WRITE")
    @PostMapping("/{id}/activate")
    public Result<BomView> activate(@PathVariable UUID id) {
        return Result.success(bomService.activate(id));
    }

    /**
     * 删除 BOM（逻辑删除）。
     *
     * @param bomId BOM 主键
     * @return 空响应
     */
    @Operation(summary = "删除 BOM", description = "状态：已实现。被订单或其它 BOM 子件行引用时返回 422/42200。")
    @RequiresPermission("MD_WRITE")
    @DeleteMapping("/{bomId}")
    public Result<Void> delete(@PathVariable UUID bomId) {
        bomService.delete(bomId);
        return Result.success();
    }

    /**
     * 新建 BOM 请求体，字段与 materials.yaml BomCreateRequest 对齐。
     *
     * @param parentMaterialCode 父件物料编码
     * @param bomVersion 业务版本号
     * @param effectiveDate 生效日期
     * @param expiryDate 失效日期
     * @param isPanel 是否拼板 BOM
     * @param panelSize 拼板数量
     * @param items 子件明细，至少一条
     */
    public record BomCreateRequest(
            @NotBlank @Size(max = 64) String parentMaterialCode,
            @NotBlank @Size(max = 32) String bomVersion,
            @NotNull LocalDate effectiveDate,
            LocalDate expiryDate,
            Boolean isPanel,
            @Min(1) Integer panelSize,
            @NotNull @Size(min = 1) @Valid List<BomItemRequest> items) {

        /**
         * 转为服务层命令。
         *
         * @return 新建命令
         */
        BomCreateCommand toCommand() {
            List<BomItemCommand> commands = items.stream().map(BomItemRequest::toCommand).toList();
            return new BomCreateCommand(parentMaterialCode, bomVersion, effectiveDate, expiryDate,
                    isPanel, panelSize, commands);
        }
    }

    /**
     * BOM 子件请求体，字段与 materials.yaml BomItem 对齐。
     *
     * @param materialCode 子件物料编码
     * @param qtyPerUnit 单件用量
     * @param scrapRate 损耗率（百分比 0–100）
     * @param position SMT 位号
     * @param substitutes 替代料物料编码数组
     * @param isPhantom 是否虚拟件
     */
    public record BomItemRequest(
            @NotBlank @Size(max = 64) String materialCode,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal qtyPerUnit,
            @DecimalMin("0.0") BigDecimal scrapRate,
            @Size(max = 128) String position,
            List<String> substitutes,
            Boolean isPhantom) {

        /**
         * 转为服务层命令。
         *
         * @return 子件命令
         */
        BomItemCommand toCommand() {
            return new BomItemCommand(materialCode, qtyPerUnit, scrapRate, position, substitutes, isPhantom);
        }
    }
}
