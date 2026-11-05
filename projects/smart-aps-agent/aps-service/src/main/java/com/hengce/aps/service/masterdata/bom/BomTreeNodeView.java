package com.hengce.aps.service.masterdata.bom;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * BOM 树节点，字段对齐 materials.yaml 的 BomTreeNode。
 *
 * @param bomId 该节点对应的 BOM 主键；子件没有 ACTIVE BOM 时为 null
 * @param parentMaterialCode 节点物料编码
 * @param materialName 节点物料名称
 * @param qtyPerUnit 相对父件的单件用量；根节点为 null
 * @param level 层级，根节点为 1
 * @param children 子节点，空列表表示叶子或被最大深度截断
 */
public record BomTreeNodeView(
        UUID bomId,
        String parentMaterialCode,
        String materialName,
        BigDecimal qtyPerUnit,
        Integer level,
        List<BomTreeNodeView> children) {
}
