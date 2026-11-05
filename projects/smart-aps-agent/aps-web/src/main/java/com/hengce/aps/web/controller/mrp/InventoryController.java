package com.hengce.aps.web.controller.mrp;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.mrp.InventoryItemCommand;
import com.hengce.aps.service.mrp.InventoryService;
import com.hengce.aps.service.mrp.InventoryUpsertResult;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存维护接口（M2 BD-01 / C25）。
 *
 * <p>{@code PUT /api/v1/inventory} 批量 upsert，以 materialCode + warehouseCode 为幂等键；
 * 重复提交只更新同一条记录。物料不存在 40400，数量为负 42200，快照回退/版本冲突 40901。
 * M2 不做库存维护页（页面归 M3），本接口供 MRP 与联调脚本使用。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "库存", description = "库存快照批量 upsert（BD-01 已实现）")
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * 构造控制器。
     *
     * @param inventoryService 库存服务
     */
    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * 批量 upsert 库存快照。
     *
     * @param request 库存请求
     * @return 契约口径的 {@code accepted}/{@code rejected} 计数
     */
    @Operation(summary = "库存批量 upsert",
            description = "状态：已实现。幂等键 materialCode+warehouseCode；onHandQty/safetyStock 不允许为负；"
                    + "响应为 {accepted, rejected}。")
    @RequiresPermission("MD_WRITE")
    @PutMapping("/inventory")
    public Result<InventoryUpsertResult> upsert(@Valid @RequestBody InventoryUpsertRequest request) {
        return Result.success(inventoryService.upsert(request.toCommands()));
    }

    /**
     * 库存 upsert 请求体。
     *
     * @param items 库存项，至少一条
     */
    public record InventoryUpsertRequest(@NotNull @Size(min = 1) List<InventoryItemRequest> items) {

        /**
         * 转换为服务层命令。
         *
         * @return 库存命令列表
         */
        List<InventoryItemCommand> toCommands() {
            return items.stream()
                    .map(item -> new InventoryItemCommand(item.materialCode(), item.warehouseCode(),
                            item.onHandQty(), item.safetyStock(), item.snapshotAt()))
                    .toList();
        }
    }

    /**
     * 库存项。
     *
     * @param materialCode 物料业务编码
     * @param warehouseCode 仓库编码，空则 DEFAULT
     * @param onHandQty 现有库存数量
     * @param safetyStock 安全库存
     * @param snapshotAt 快照时间（ISO-8601 UTC），空则当前时间
     */
    public record InventoryItemRequest(@NotBlank String materialCode, String warehouseCode,
                                       @NotNull BigDecimal onHandQty, BigDecimal safetyStock,
                                       OffsetDateTime snapshotAt) {
    }
}
