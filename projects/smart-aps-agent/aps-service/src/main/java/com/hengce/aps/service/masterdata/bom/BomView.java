package com.hengce.aps.service.masterdata.bom;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * BOM 出参，字段对齐 materials.yaml 的 Bom；{@code items} 来自 {@code bom_item} 事实表。
 *
 * @param id BOM 主键
 * @param parentMaterialCode 父件物料编码
 * @param bomVersion 业务版本号
 * @param effectiveDate 生效日期
 * @param expiryDate 失效日期，可空
 * @param status 状态
 * @param isPanel 是否拼板 BOM
 * @param panelSize 拼板数量
 * @param items 完整子件明细
 * @param version 乐观锁版本
 */
public record BomView(
        UUID id,
        String parentMaterialCode,
        String bomVersion,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        BomStatus status,
        Boolean isPanel,
        Integer panelSize,
        List<BomItemView> items,
        Integer version) {
}
