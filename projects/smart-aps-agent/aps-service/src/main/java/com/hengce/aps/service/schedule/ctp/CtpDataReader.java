package com.hengce.aps.service.schedule.ctp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.bom.BomItemMapper;
import com.hengce.aps.infrastructure.persistence.bom.BomMapper;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryMapper;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransit;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryTransitMapper;
import com.hengce.aps.infrastructure.persistence.mrp.MrpMasterDataRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * CTP 物料侧只读数据读取器：只做查询，不写任何业务表。
 *
 * <p>BOM/物料复用 {@link MrpMasterDataRepository}（与 MRP 同口径）；库存与在途直接复用既有只读
 * Mapper；替代料反查仅读取 {@code bom_item} 与 ACTIVE {@code bom}，不修改既有服务。</p>
 */
@Repository
public class CtpDataReader {

    private static final String BOM_ACTIVE = "ACTIVE";

    private final MrpMasterDataRepository masterDataRepository;

    private final BomMapper bomMapper;

    private final BomItemMapper bomItemMapper;

    private final InventoryMapper inventoryMapper;

    private final InventoryTransitMapper inventoryTransitMapper;

    /**
     * 构造读取器。
     *
     * @param masterDataRepository BOM/物料只读仓库
     * @param bomMapper BOM 主表 Mapper（只读）
     * @param bomItemMapper BOM 明细 Mapper（只读）
     * @param inventoryMapper 库存 Mapper（只读）
     * @param inventoryTransitMapper 在途 Mapper（只读）
     */
    public CtpDataReader(MrpMasterDataRepository masterDataRepository, BomMapper bomMapper,
                         BomItemMapper bomItemMapper, InventoryMapper inventoryMapper,
                         InventoryTransitMapper inventoryTransitMapper) {
        this.masterDataRepository = masterDataRepository;
        this.bomMapper = bomMapper;
        this.bomItemMapper = bomItemMapper;
        this.inventoryMapper = inventoryMapper;
        this.inventoryTransitMapper = inventoryTransitMapper;
    }

    /**
     * 按编码批量查询物料。
     *
     * @param codes 物料编码集合
     * @return 编码到物料的映射
     */
    public Map<String, Material> findMaterialsByCodes(Collection<String> codes) {
        return masterDataRepository.findMaterialsByCodes(codes);
    }

    /**
     * 按主键批量查询物料。
     *
     * @param materialIds 物料主键集合
     * @return 主键到物料的映射
     */
    public Map<UUID, Material> findMaterials(Collection<UUID> materialIds) {
        return masterDataRepository.findMaterials(materialIds);
    }

    /**
     * 查询父件的 ACTIVE BOM（按生效日期倒序，取第一条即最新版本）。
     *
     * @param parentMaterialId 父件物料主键
     * @return ACTIVE BOM 列表
     */
    public List<Bom> findActiveBoms(UUID parentMaterialId) {
        return masterDataRepository.findActiveBoms(parentMaterialId);
    }

    /**
     * 查询 BOM 明细。
     *
     * @param bomId BOM 主键
     * @return 明细列表
     */
    public List<BomItem> findBomItems(UUID bomId) {
        return masterDataRepository.findItems(bomId);
    }

    /**
     * 批量查询库存快照。
     *
     * @param materialIds 物料主键集合
     * @return 库存行
     */
    public List<Inventory> findInventory(Collection<UUID> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            return List.of();
        }
        return inventoryMapper.selectList(Wrappers.<Inventory>lambdaQuery()
                .in(Inventory::getMaterialId, materialIds));
    }

    /**
     * 批量查询未取消在途。
     *
     * @param materialIds 物料主键集合
     * @return 在途行（含 CANCELLED，由调用方按 C49 口径过滤）
     */
    public List<InventoryTransit> findTransit(Collection<UUID> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            return List.of();
        }
        return inventoryTransitMapper.selectList(Wrappers.<InventoryTransit>lambdaQuery()
                .in(InventoryTransit::getMaterialId, materialIds));
    }

    /**
     * 反查“以该物料为子件”的 ACTIVE BOM 明细行，用于取替代料登记。
     *
     * <p>{@code bom_item.substitutes} 登记在父件 BOM 行上；直接询单某个原材料时，
     * 需要按子件反查它在 ACTIVE BOM 中的替代关系，不能只依赖本次展开路径。</p>
     *
     * @param childMaterialIds 子件物料主键集合
     * @return ACTIVE BOM 下的明细行
     */
    public List<BomItem> findIncomingItems(Collection<UUID> childMaterialIds) {
        if (childMaterialIds == null || childMaterialIds.isEmpty()) {
            return List.of();
        }
        List<BomItem> rows = bomItemMapper.selectList(Wrappers.<BomItem>lambdaQuery()
                .in(BomItem::getChildMaterialId, childMaterialIds));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> bomIds = new HashSet<>();
        for (BomItem row : rows) {
            if (row.getBomId() != null) {
                bomIds.add(row.getBomId());
            }
        }
        Map<UUID, Bom> activeBoms = new HashMap<>();
        if (!bomIds.isEmpty()) {
            for (Bom bom : bomMapper.selectBatchIds(bomIds)) {
                if (BOM_ACTIVE.equals(bom.getStatus())) {
                    activeBoms.put((UUID) bom.getId(), bom);
                }
            }
        }
        return rows.stream().filter(row -> activeBoms.containsKey(row.getBomId())).toList();
    }
}
