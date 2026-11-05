package com.hengce.aps.service.mrp;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.mrp.Inventory;
import com.hengce.aps.infrastructure.persistence.mrp.InventoryMapper;
import com.hengce.aps.infrastructure.persistence.mrp.MrpMasterDataRepository;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存 upsert 单元测试：幂等更新、负数校验、物料缺失、快照回退与乐观锁冲突。
 */
class InventoryServiceTest {

    private InventoryMapper inventoryMapper;

    private MrpMasterDataRepository masterDataRepository;

    private InventoryService inventoryService;

    private Material material;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        TableInfoHelper.initTableInfo(assistant, Inventory.class);
        TableInfoHelper.initTableInfo(assistant, Material.class);
        inventoryMapper = mock(InventoryMapper.class);
        masterDataRepository = mock(MrpMasterDataRepository.class);
        inventoryService = new InventoryService(inventoryMapper, masterDataRepository,
                new MrpSuggestionCache(45));
        material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode("RM-01");
        material.setName("原料");
        when(masterDataRepository.findMaterialsByCodes(any()))
                .thenReturn(Map.of("RM-01", material));
    }

    @Test
    void repeatedSubmitUpdatesSameRecordWithoutDuplicateRows() {
        Inventory existing = new Inventory();
        existing.setId(UUID.randomUUID());
        existing.setMaterialId((UUID) material.getId());
        existing.setWarehouseCode("DEFAULT");
        existing.setOnHandQty(new BigDecimal("5"));
        existing.setSafetyStock(BigDecimal.ZERO);
        existing.setSnapshotAt(OffsetDateTime.of(2026, 11, 3, 0, 0, 0, 0, ZoneOffset.UTC));
        when(inventoryMapper.selectOne(any())).thenReturn(null, existing);
        when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
        when(inventoryMapper.updateById(any(Inventory.class))).thenReturn(1);

        InventoryItemCommand command = new InventoryItemCommand("RM-01", "DEFAULT",
                new BigDecimal("10"), new BigDecimal("2"),
                OffsetDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneOffset.UTC));
        InventoryUpsertResult first = inventoryService.upsert(List.of(command));
        assertEquals(new InventoryUpsertResult(1, 0), first);
        InventoryUpsertResult second = inventoryService.upsert(List.of(command));

        assertEquals(new InventoryUpsertResult(1, 0), second);
        verify(inventoryMapper, org.mockito.Mockito.times(1)).insert(any(Inventory.class));
        verify(inventoryMapper, org.mockito.Mockito.times(1)).updateById(any(Inventory.class));
        assertEquals(0, new BigDecimal("10").compareTo(existing.getOnHandQty()));
    }

    @Test
    void rejectsNegativeQuantity() {
        BizException exception = assertThrows(BizException.class, () -> inventoryService.upsert(
                List.of(new InventoryItemCommand("RM-01", "DEFAULT", new BigDecimal("-1"),
                        BigDecimal.ZERO, null))));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(inventoryMapper, never()).insert(any(Inventory.class));
    }

    @Test
    void rejectsUnknownMaterial() {
        BizException exception = assertThrows(BizException.class, () -> inventoryService.upsert(
                List.of(new InventoryItemCommand("RM-404", "DEFAULT", BigDecimal.ONE,
                        BigDecimal.ZERO, null))));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void rejectsDuplicateMaterialWarehouseInSameRequest() {
        BizException exception = assertThrows(BizException.class, () -> inventoryService.upsert(
                List.of(new InventoryItemCommand("RM-01", "DEFAULT", BigDecimal.ONE,
                                BigDecimal.ZERO, null),
                        new InventoryItemCommand("RM-01", "DEFAULT", new BigDecimal("2"),
                                BigDecimal.ZERO, null))));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
    }

    @Test
    void rejectsSnapshotRollback() {
        Inventory existing = new Inventory();
        existing.setSnapshotAt(OffsetDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneOffset.UTC));
        when(inventoryMapper.selectOne(any())).thenReturn(existing);

        BizException exception = assertThrows(BizException.class, () -> inventoryService.upsert(
                List.of(new InventoryItemCommand("RM-01", "DEFAULT", BigDecimal.ONE,
                        BigDecimal.ZERO,
                        OffsetDateTime.of(2026, 11, 3, 0, 0, 0, 0, ZoneOffset.UTC)))));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        verify(inventoryMapper, never()).updateById(any(Inventory.class));
    }

    @Test
    void reportsConflictWhenOptimisticUpdateAffectsNoRow() {
        when(inventoryMapper.selectOne(any())).thenReturn(new Inventory());
        when(inventoryMapper.updateById(any(Inventory.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class, () -> inventoryService.upsert(
                List.of(new InventoryItemCommand("RM-01", "DEFAULT", BigDecimal.ONE,
                        BigDecimal.ZERO, null))));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void reportsConflictWhenConcurrentInsertHitsUniqueIndex() {
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(inventoryMapper.insert(any(Inventory.class))).thenThrow(new DuplicateKeyException("duplicate"));

        BizException exception = assertThrows(BizException.class, () -> inventoryService.upsert(
                List.of(new InventoryItemCommand("RM-01", "DEFAULT", BigDecimal.ONE,
                        BigDecimal.ZERO, null))));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }
}
