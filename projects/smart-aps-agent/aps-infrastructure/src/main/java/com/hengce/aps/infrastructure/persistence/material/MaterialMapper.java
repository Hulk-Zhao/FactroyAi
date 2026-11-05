package com.hengce.aps.infrastructure.persistence.material;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 物料 Mapper。
 *
 * <p>引用计数查询为只读最小 SQL，供删除前校验使用；不依赖 BOM/订单/工艺路线流尚未落地的 Mapper。
 * tenant_id 由 BD-01 的租户拦截器统一追加。</p>
 */
public interface MaterialMapper extends BaseMapper<Material> {

    /**
     * 统计以该物料为父件的 BOM 数量。
     *
     * @param materialId 物料主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM bom WHERE parent_material_id = #{materialId} AND deleted = false")
    long countBomReferences(@Param("materialId") UUID materialId);

    /**
     * 统计以该物料为子件的 BOM 行数量。
     *
     * @param materialId 物料主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM bom_item WHERE child_material_id = #{materialId} AND deleted = false")
    long countBomItemReferences(@Param("materialId") UUID materialId);

    /**
     * 统计引用该物料的订单数量。
     *
     * @param materialId 物料主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM orders WHERE product_id = #{materialId} AND deleted = false")
    long countOrderReferences(@Param("materialId") UUID materialId);

    /**
     * 统计引用该物料的工艺路线数量。
     *
     * @param materialId 物料主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM routing WHERE product_id = #{materialId} AND deleted = false")
    long countRoutingReferences(@Param("materialId") UUID materialId);
}
