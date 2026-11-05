package com.hengce.aps.service.mrp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryMapper;
import com.hengce.aps.infrastructure.persistence.mrp.MrpMasterDataRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存批量 upsert（C25 / R2）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>以 {@code (tenant, materialCode, warehouseCode)} 为幂等键；重复提交只更新同一条记录，
 *       不产生重复行。</li>
 *   <li>物料不存在返回 40400；数量或安全库存为负返回 42200；请求内重复键返回 42200。</li>
 *   <li>快照时间早于库内已有快照、或乐观锁版本冲突返回 40901。</li>
 * </ul>
 */
@Service
public class InventoryService {

    /**
     * 未指定仓库时使用的默认仓库编码。
     */
    public static final String DEFAULT_WAREHOUSE = "DEFAULT";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InventoryMapper inventoryMapper;

    private final MrpMasterDataRepository masterDataRepository;

    private final MrpSuggestionCache suggestionCache;

    /**
     * 构造服务。
     *
     * @param inventoryMapper 库存 Mapper
     * @param masterDataRepository 只读主数据仓库（物料编码寻址）
     * @param suggestionCache 采购建议缓存（库存变化后主动失效）
     */
    public InventoryService(InventoryMapper inventoryMapper,
                            MrpMasterDataRepository masterDataRepository,
                            MrpSuggestionCache suggestionCache) {
        this.inventoryMapper = inventoryMapper;
        this.masterDataRepository = masterDataRepository;
        this.suggestionCache = suggestionCache;
    }

    /**
     * 批量 upsert 库存快照。
     *
     * @param items 库存项，至少一条
     * @return 契约口径的 {@code accepted}/{@code rejected} 计数
     */
    @Transactional(rollbackFor = Exception.class)
    public InventoryUpsertResult upsert(List<InventoryItemCommand> items) {
        List<InventoryItemCommand> normalized = normalize(items);
        Map<String, Material> materials = loadMaterials(normalized);
        for (InventoryItemCommand item : normalized) {
            String materialCode = item.materialCode().trim();
            String warehouseCode = warehouse(item.warehouseCode());
            Material material = materials.get(materialCode);
            UUID materialId = (UUID) material.getId();
            Inventory existing = inventoryMapper.selectOne(Wrappers.<Inventory>lambdaQuery()
                    .eq(Inventory::getMaterialId, materialId)
                    .eq(Inventory::getWarehouseCode, warehouseCode));
            if (existing == null) {
                Inventory record = buildRecord(item, materialId, warehouseCode);
                try {
                    inventoryMapper.insert(record);
                } catch (DuplicateKeyException exception) {
                    throw conflict("库存记录已被并发创建，请重试: " + materialCode + "/" + warehouseCode);
                }
                continue;
            }
            applySnapshot(item, existing, materialCode, warehouseCode);
            if (inventoryMapper.updateById(existing) == 0) {
                throw conflict("库存记录版本已变化，请刷新后重试: " + materialCode + "/" + warehouseCode);
            }
        }
        // 整体事务语义：全部条目受理成功，rejected 恒为 0；任一条失败走异常路径，不返回计数。
        suggestionCache.invalidate(TenantContext.getTenantId());
        return new InventoryUpsertResult(normalized.size(), 0);
    }

    private List<InventoryItemCommand> normalize(List<InventoryItemCommand> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "items 至少需要一条库存记录");
        }
        List<InventoryItemCommand> normalized = new ArrayList<>(items.size());
        Set<String> keys = new HashSet<>();
        for (InventoryItemCommand item : items) {
            if (item == null || item.materialCode() == null || item.materialCode().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "materialCode 不能为空");
            }
            String materialCode = item.materialCode().trim();
            if (item.onHandQty() == null || item.onHandQty().signum() < 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "onHandQty 不能为负: " + materialCode);
            }
            if (item.safetyStock() != null && item.safetyStock().signum() < 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "safetyStock 不能为负: " + materialCode);
            }
            String warehouseCode = warehouse(item.warehouseCode());
            String key = materialCode + "\u0000" + warehouseCode;
            if (!keys.add(key)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "同一请求内存在重复的物料+仓库: " + materialCode + "/" + warehouseCode);
            }
            normalized.add(new InventoryItemCommand(materialCode, warehouseCode,
                    item.onHandQty(), item.safetyStock() == null ? ZERO : item.safetyStock(),
                    item.snapshotAt()));
        }
        return normalized;
    }

    private Map<String, Material> loadMaterials(List<InventoryItemCommand> items) {
        Set<String> codes = new LinkedHashSet<>();
        for (InventoryItemCommand item : items) {
            codes.add(item.materialCode());
        }
        Map<String, Material> byCode = masterDataRepository.findMaterialsByCodes(codes);
        List<String> missing = codes.stream().filter(code -> !byCode.containsKey(code)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "物料不存在: " + String.join(", ", missing));
        }
        return byCode;
    }

    private Inventory buildRecord(InventoryItemCommand item, UUID materialId, String warehouseCode) {
        Inventory record = new Inventory();
        record.setId(UUID.randomUUID());
        record.setMaterialId(materialId);
        record.setWarehouseCode(warehouseCode);
        record.setOnHandQty(item.onHandQty());
        record.setSafetyStock(item.safetyStock());
        record.setSnapshotAt(item.snapshotAt() == null ? now() : item.snapshotAt());
        return record;
    }

    private void applySnapshot(InventoryItemCommand item, Inventory existing, String materialCode,
                               String warehouseCode) {
        OffsetDateTime snapshotAt = item.snapshotAt() == null ? now() : item.snapshotAt();
        if (existing.getSnapshotAt() != null && snapshotAt.isBefore(existing.getSnapshotAt())) {
            throw conflict("库存快照时间早于已有快照，拒绝覆盖: " + materialCode + "/" + warehouseCode);
        }
        existing.setOnHandQty(item.onHandQty());
        existing.setSafetyStock(item.safetyStock());
        existing.setSnapshotAt(snapshotAt);
    }

    private String warehouse(String warehouseCode) {
        return warehouseCode == null || warehouseCode.isBlank()
                ? DEFAULT_WAREHOUSE : warehouseCode.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private BizException conflict(String message) {
        return new BizException(ErrorCode.STATE_CONFLICT, message);
    }
}
