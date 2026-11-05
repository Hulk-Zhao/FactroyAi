package com.hengce.aps.service.masterdata.bom;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新建 BOM 子件命令（对应 materials.yaml BomItem 的请求字段）。
 *
 * @param materialCode 子件物料编码
 * @param qtyPerUnit 单件用量，必须大于 0
 * @param scrapRate 损耗率（百分比 0–100）
 * @param position SMT 位号
 * @param substitutes 替代料物料编码数组
 * @param isPhantom 是否虚拟件
 */
public record BomItemCommand(
        String materialCode,
        BigDecimal qtyPerUnit,
        BigDecimal scrapRate,
        String position,
        List<String> substitutes,
        Boolean isPhantom) {
}
