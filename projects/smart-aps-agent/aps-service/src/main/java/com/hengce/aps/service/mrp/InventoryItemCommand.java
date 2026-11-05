package com.hengce.aps.service.mrp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 库存 upsert 单项命令，对应 {@code PUT /api/v1/inventory} 的 items 元素。
 *
 * @param materialCode 物料业务编码
 * @param warehouseCode 仓库编码，空则按 DEFAULT 处理
 * @param onHandQty 现有库存数量
 * @param safetyStock 安全库存
 * @param snapshotAt 库存快照时间，空则取当前 UTC 时间
 */
public record InventoryItemCommand(String materialCode, String warehouseCode, BigDecimal onHandQty,
                                   BigDecimal safetyStock, OffsetDateTime snapshotAt) {
}
