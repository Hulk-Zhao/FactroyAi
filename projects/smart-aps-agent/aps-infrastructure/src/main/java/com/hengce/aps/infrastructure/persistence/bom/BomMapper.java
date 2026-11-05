package com.hengce.aps.infrastructure.persistence.bom;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * BOM 主表 Mapper。
 *
 * <p>租户过滤由 BD-01 的 TenantLineInnerInterceptor 统一追加，逻辑删除由 {@code @TableLogic} 处理；
 * 自定义只读 SQL 仅用于删除前的引用计数（订单按 product_id 间接引用 BOM 父件）。</p>
 */
public interface BomMapper extends BaseMapper<Bom> {

    /**
     * 统计以该物料为产品的订单数量（订单经 product_id 间接引用 BOM 父件）。
     *
     * @param productId 物料主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM orders WHERE product_id = #{productId} AND deleted = false")
    long countOrderReferences(@Param("productId") UUID productId);
}
