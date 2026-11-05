package com.hengce.aps.service.masterdata.material;

import com.hengce.aps.domain.masterdata.material.MaterialType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 创建物料命令。
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
public record MaterialCreateCommand(
        String code,
        String name,
        MaterialType type,
        String spec,
        String unit,
        UUID categoryId,
        BigDecimal safetyStock,
        BigDecimal moq,
        Integer leadTimeDays,
        UUID defaultSupplierId,
        Map<String, Object> extAttrs,
        Map<String, Object> industryAttrs) {
}
