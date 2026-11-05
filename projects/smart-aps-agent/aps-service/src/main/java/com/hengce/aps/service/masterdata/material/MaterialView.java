package com.hengce.aps.service.masterdata.material;

import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.infrastructure.persistence.material.Material;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 物料返回视图，字段与 materials.yaml 的 Material 模式一致（不暴露内部主键与租户字段）。
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
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record MaterialView(
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
        Map<String, Object> industryAttrs,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 由持久化实体转换。
     *
     * @param material 物料实体
     * @return 返回视图
     */
    public static MaterialView from(Material material) {
        return new MaterialView(
                material.getCode(),
                material.getName(),
                material.getType(),
                material.getSpec(),
                material.getUnit(),
                material.getCategoryId(),
                material.getSafetyStock(),
                material.getMoq(),
                material.getLeadTimeDays(),
                material.getDefaultSupplierId(),
                material.getExtAttrs(),
                material.getIndustryAttrs(),
                material.getVersion(),
                material.getCreatedAt(),
                material.getUpdatedAt());
    }
}
