package com.hengce.aps.service.masterdata.bom;

import java.time.LocalDate;
import java.util.List;

/**
 * 新建 BOM 命令（对应 materials.yaml BomCreateRequest）。
 *
 * @param parentMaterialCode 父件物料编码
 * @param bomVersion 业务版本号
 * @param effectiveDate 生效日期
 * @param expiryDate 失效日期，可空
 * @param isPanel 是否拼板 BOM
 * @param panelSize 拼板数量
 * @param items 子件明细，至少一条
 */
public record BomCreateCommand(
        String parentMaterialCode,
        String bomVersion,
        LocalDate effectiveDate,
        LocalDate expiryDate,
        Boolean isPanel,
        Integer panelSize,
        List<BomItemCommand> items) {
}
