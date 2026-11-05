package com.hengce.aps.infrastructure.persistence.mrp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.bom.BomItemMapper;
import com.hengce.aps.infrastructure.persistence.bom.BomMapper;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * MRP 只读主数据仓库：封装 BOM 与物料查询，避免服务层直接拼装 Mapper 条件。
 *
 * <p>仅复用既有只读 Mapper，不修改任何主数据模块的实体或写入逻辑；
 * 租户条件由 MyBatis-Plus 租户拦截器统一追加。</p>
 */
@Repository
public class MrpMasterDataRepository {

    private static final String BOM_ACTIVE = "ACTIVE";

    private final BomMapper bomMapper;

    private final BomItemMapper bomItemMapper;

    private final MaterialMapper materialMapper;

    private final SupplierRefMapper supplierRefMapper;

    /**
     * 构造仓库。
     *
     * @param bomMapper BOM 主表 Mapper
     * @param bomItemMapper BOM 明细 Mapper
     * @param materialMapper 物料 Mapper
     * @param supplierRefMapper 供应商只读 Mapper
     */
    public MrpMasterDataRepository(BomMapper bomMapper, BomItemMapper bomItemMapper,
                                   MaterialMapper materialMapper, SupplierRefMapper supplierRefMapper) {
        this.bomMapper = bomMapper;
        this.bomItemMapper = bomItemMapper;
        this.materialMapper = materialMapper;
        this.supplierRefMapper = supplierRefMapper;
    }

    /**
     * 查询父件的全部 ACTIVE BOM，按生效日期倒序。
     *
     * @param parentMaterialId 父件物料主键
     * @return ACTIVE BOM 列表，无结果返回空列表
     */
    public List<Bom> findActiveBoms(UUID parentMaterialId) {
        return bomMapper.selectList(Wrappers.<Bom>lambdaQuery()
                .eq(Bom::getParentMaterialId, parentMaterialId)
                .eq(Bom::getStatus, BOM_ACTIVE)
                .orderByDesc(Bom::getEffectiveDate));
    }

    /**
     * 查询 BOM 明细，按行序号升序。
     *
     * @param bomId BOM 主键
     * @return 明细列表
     */
    public List<BomItem> findItems(UUID bomId) {
        return bomItemMapper.selectList(Wrappers.<BomItem>lambdaQuery()
                .eq(BomItem::getBomId, bomId)
                .orderByAsc(BomItem::getSeqNo));
    }

    /**
     * 按主键查询单个物料。
     *
     * @param materialId 物料主键
     * @return 物料，不存在返回 null
     */
    public Material findMaterial(UUID materialId) {
        return materialMapper.selectById(materialId);
    }

    /**
     * 按主键批量查询物料。
     *
     * @param materialIds 物料主键集合
     * @return 主键到物料的映射
     */
    public Map<UUID, Material> findMaterials(Collection<UUID> materialIds) {
        Map<UUID, Material> materials = new HashMap<>();
        if (materialIds == null || materialIds.isEmpty()) {
            return materials;
        }
        for (Material material : materialMapper.selectBatchIds(materialIds)) {
            materials.put((UUID) material.getId(), material);
        }
        return materials;
    }

    /**
     * 按业务编码批量查询物料。
     *
     * @param codes 物料编码集合
     * @return 编码到物料的映射
     */
    public Map<String, Material> findMaterialsByCodes(Collection<String> codes) {
        Map<String, Material> materials = new HashMap<>();
        if (codes == null || codes.isEmpty()) {
            return materials;
        }
        for (Material material : materialMapper.selectList(Wrappers.<Material>lambdaQuery()
                .in(Material::getCode, codes))) {
            materials.put(material.getCode(), material);
        }
        return materials;
    }

    /**
     * 按主键批量查询供应商编码。
     *
     * @param supplierIds 供应商主键集合
     * @return 主键到供应商编码的映射
     */
    public Map<UUID, String> findSupplierCodes(Collection<UUID> supplierIds) {
        Map<UUID, String> codes = new HashMap<>();
        if (supplierIds == null || supplierIds.isEmpty()) {
            return codes;
        }
        for (SupplierRef supplier : supplierRefMapper.selectBatchIds(supplierIds)) {
            codes.put((UUID) supplier.getId(), supplier.getCode());
        }
        return codes;
    }
}
