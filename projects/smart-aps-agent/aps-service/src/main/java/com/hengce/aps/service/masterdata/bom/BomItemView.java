package com.hengce.aps.service.masterdata.bom;

import java.math.BigDecimal;
import java.util.List;

/**
 * BOM 子件出参，字段对齐 materials.yaml 的 BomItem（substitutes 为物料编码数组）。
 *
 * @param materialCode 子件物料编码
 * @param qtyPerUnit 单件用量
 * @param scrapRate 损耗率
 * @param position SMT 位号
 * @param substitutes 替代料物料编码数组，空值归一化为空列表
 * @param isPhantom 是否虚拟件
 */
public record BomItemView(
        String materialCode,
        BigDecimal qtyPerUnit,
        BigDecimal scrapRate,
        String position,
        List<String> substitutes,
        Boolean isPhantom) {
}
