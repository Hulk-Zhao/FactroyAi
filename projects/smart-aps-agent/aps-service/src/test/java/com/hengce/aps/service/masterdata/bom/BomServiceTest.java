package com.hengce.aps.service.masterdata.bom;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.bom.BomItemMapper;
import com.hengce.aps.infrastructure.persistence.bom.BomMapper;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import com.hengce.aps.service.mrp.MrpSuggestionCache;
import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOM 服务单元测试：同版本重复、缺组件、双 ACTIVE 冲突、环检测、删除引用与 C10 组合查询。
 *
 * <p>全部使用 Mockito，不依赖真实数据库；LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存，
 * 与 MaterialServiceTest 一致在测试初始化时手工注册。</p>
 */
class BomServiceTest {

    private BomMapper bomMapper;

    private BomItemMapper bomItemMapper;

    private MaterialMapper materialMapper;

    private BomService bomService;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        TableInfoHelper.initTableInfo(assistant, Bom.class);
        TableInfoHelper.initTableInfo(assistant, BomItem.class);
        TableInfoHelper.initTableInfo(assistant, Material.class);
        bomMapper = mock(BomMapper.class);
        bomItemMapper = mock(BomItemMapper.class);
        materialMapper = mock(MaterialMapper.class);
        bomService = new BomService(bomMapper, bomItemMapper, materialMapper,
                new MrpSuggestionCache(45));
    }

    @Test
    void createShouldRejectSameParentSameVersionWith40900() {
        Material parent = material("M-P");
        Material child = material("M-C");
        Material substitute = material("M-SUB");
        when(materialMapper.selectOne(any())).thenReturn(parent);
        when(materialMapper.selectList(any())).thenReturn(List.of(parent, child, substitute));
        when(bomMapper.selectCount(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class,
                () -> bomService.create(createCommand()));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, exception.getErrorCode());
        assertEquals(40900, exception.getErrorCode().getCode());
        verify(bomMapper, never()).insert(any(Bom.class));
    }

    @Test
    void createShouldRejectMissingComponentWith42200() {
        Material parent = material("M-P");
        when(materialMapper.selectOne(any())).thenReturn(parent);
        when(materialMapper.selectList(any())).thenReturn(List.of(parent));
        BizException exception = assertThrows(BizException.class,
                () -> bomService.create(createCommand()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("M-C"), exception.getMessage());
        verify(bomMapper, never()).insert(any(Bom.class));
    }

    @Test
    void createShouldInsertDraftBomWithItemsAndSnapshot() {
        Material parent = material("M-P");
        Material child = material("M-C");
        Material substitute = material("M-SUB");
        when(materialMapper.selectOne(any())).thenReturn(parent);
        when(materialMapper.selectList(any())).thenReturn(List.of(parent, child, substitute));
        when(bomMapper.selectCount(any())).thenReturn(0L);
        BomView view = bomService.create(createCommand());
        ArgumentCaptor<Bom> bomCaptor = ArgumentCaptor.forClass(Bom.class);
        verify(bomMapper).insert(bomCaptor.capture());
        Bom inserted = bomCaptor.getValue();
        assertTrue(inserted.getId() instanceof UUID);
        assertEquals(BomStatus.DRAFT.name(), inserted.getStatus());
        assertNotNull(inserted.getItems());
        assertEquals(1, inserted.getItems().size());
        assertEquals("M-C", inserted.getItems().get(0).get("materialCode"));
        ArgumentCaptor<BomItem> itemCaptor = ArgumentCaptor.forClass(BomItem.class);
        verify(bomItemMapper).insert(itemCaptor.capture());
        assertEquals(new BigDecimal("2"), itemCaptor.getValue().getQtyPerUnit());
        assertEquals("M-C", view.items().get(0).materialCode());
        assertEquals(BomStatus.DRAFT, view.status());
    }

    @Test
    void createShouldMapRawMyBatisUniqueViolationTo40900() {
        Material parent = material("M-P");
        Material child = material("M-C");
        Material substitute = material("M-SUB");
        when(materialMapper.selectOne(any())).thenReturn(parent);
        when(materialMapper.selectList(any())).thenReturn(List.of(parent, child, substitute));
        when(bomMapper.selectCount(any())).thenReturn(0L);
        when(bomMapper.insert(any(Bom.class))).thenThrow(new PersistenceException("insert failed",
                new SQLException("duplicate key value violates unique constraint \"uq_bom_parent_version\"",
                        "23505")));
        BizException exception = assertThrows(BizException.class, () -> bomService.create(createCommand()));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, exception.getErrorCode());
        assertEquals(40900, exception.getErrorCode().getCode());
        verify(bomItemMapper, never()).insert(any(BomItem.class));
    }

    @Test
    void activateShouldRejectBomWithoutItemsWith42200() {
        Bom target = bom(BomStatus.DRAFT);
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomItemMapper.selectCount(any())).thenReturn(0L);
        BizException exception = assertThrows(BizException.class, () -> bomService.activate(targetId(target)));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(bomMapper, never()).updateById(any(Bom.class));
    }

    @Test
    void activateShouldMapDuplicateActiveTo40901() {
        Bom target = bom(BomStatus.DRAFT);
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomItemMapper.selectCount(any())).thenReturn(1L);
        when(bomMapper.updateById(any(Bom.class))).thenThrow(new DuplicateKeyException("uq_bom_one_active"));
        BizException exception = assertThrows(BizException.class, () -> bomService.activate(targetId(target)));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertEquals(40901, exception.getErrorCode().getCode());
    }

    @Test
    void activateShouldMapRawMyBatisUniqueViolationTo40901() {
        Bom target = bom(BomStatus.DRAFT);
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomItemMapper.selectCount(any())).thenReturn(1L);
        SQLException sqlException = new SQLException(
                "duplicate key value violates unique constraint \"uq_bom_one_active_per_parent\"", "23505");
        when(bomMapper.updateById(any(Bom.class)))
                .thenThrow(new PersistenceException("update failed", sqlException));
        BizException exception = assertThrows(BizException.class, () -> bomService.activate(targetId(target)));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertEquals(40901, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("请刷新后重试"), exception.getMessage());
    }

    @Test
    void activateShouldNotSwallowNonUniquePersistenceFailure() {
        Bom target = bom(BomStatus.DRAFT);
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomItemMapper.selectCount(any())).thenReturn(1L);
        when(bomMapper.updateById(any(Bom.class))).thenThrow(
                new PersistenceException("boom", new SQLException("connection reset", "08006")));
        assertThrows(PersistenceException.class, () -> bomService.activate(targetId(target)));
    }

    @Test
    void activateShouldDeactivatePreviousActiveAndReturnActiveView() {
        Bom target = bom(BomStatus.DRAFT);
        BomItem item = item(targetId(target), UUID.randomUUID());
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomItemMapper.selectCount(any())).thenReturn(1L);
        when(bomMapper.update(any(), any())).thenReturn(1);
        when(bomMapper.updateById(any(Bom.class))).thenReturn(1);
        when(bomItemMapper.selectList(any())).thenReturn(List.of(item));
        when(materialMapper.selectBatchIds(any())).thenReturn(List.of(material("M-P"), material("M-C")));
        BomView view = bomService.activate(targetId(target));
        verify(bomMapper).update(any(), any());
        verify(bomMapper).updateById(any(Bom.class));
        assertEquals(BomStatus.ACTIVE, view.status());
    }

    @Test
    void treeShouldDetectCycleAndReportPath() {
        UUID rootBomId = UUID.randomUUID();
        UUID childBomId = UUID.randomUUID();
        Material rootMaterial = material("M-A");
        Material childMaterial = material("M-B");
        Bom root = bom(rootBomId, rootMaterial, BomStatus.DRAFT);
        Bom child = bom(childBomId, childMaterial, BomStatus.ACTIVE);
        BomItem rootItem = item(rootBomId, childMaterial);
        BomItem childItem = item(childBomId, rootMaterial);
        Map<UUID, List<BomItem>> itemsByBom = Map.of(rootBomId, List.of(rootItem), childBomId, List.of(childItem));
        when(bomMapper.selectById(rootBomId)).thenReturn(root);
        when(bomItemMapper.selectList(any())).thenAnswer(invocation -> {
            UUID bomId = firstUuid(invocation.getArgument(0));
            return itemsByBom.getOrDefault(bomId, List.of());
        });
        when(bomMapper.selectList(any())).thenAnswer(invocation -> {
            UUID parentId = firstUuid(invocation.getArgument(0));
            return parentId.equals(childMaterial.getId()) ? List.of(child) : List.of();
        });
        when(materialMapper.selectById(any())).thenAnswer(invocation -> {
            Serializable id = invocation.getArgument(0);
            return rootMaterial.getId().equals(id) ? rootMaterial : childMaterial;
        });
        BizException exception = assertThrows(BizException.class, () -> bomService.tree(rootBomId));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("循环引用"), exception.getMessage());
        assertTrue(exception.getMessage().contains("M-A -> M-B -> M-A"), exception.getMessage());
    }

    @Test
    void listWrapperShouldApplyExactFiltersWithAndSemantics() {
        UUID parentMaterialId = UUID.randomUUID();
        LambdaQueryWrapper<Bom> wrapper = bomService.buildListWrapper(parentMaterialId, BomStatus.ACTIVE);
        String sql = wrapper.getSqlSegment().replaceAll("\\s+", " ");
        assertTrue(sql.contains("parent_material_id ="), sql);
        assertTrue(sql.contains("status ="), sql);
        assertTrue(sql.contains(" AND "), sql);
        assertEquals(2, wrapper.getParamNameValuePairs().size());
    }

    @Test
    void listWrapperShouldKeepOnlyStatusWhenParentAbsent() {
        LambdaQueryWrapper<Bom> wrapper = bomService.buildListWrapper(null, BomStatus.ACTIVE);
        String sql = wrapper.getSqlSegment().replaceAll("\\s+", " ");
        assertTrue(sql.contains("status ="), sql);
        assertFalse(sql.contains("parent_material_id"), sql);
    }

    @Test
    void deleteShouldBeBlockedByOrderReference() {
        Bom target = bom(BomStatus.DRAFT);
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomMapper.countOrderReferences(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class, () -> bomService.delete(targetId(target)));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("订单"), exception.getMessage());
        verify(bomMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void deleteShouldRemoveItemsThenBomWhenNotReferenced() {
        Bom target = bom(BomStatus.DRAFT);
        when(bomMapper.selectById(any())).thenReturn(target);
        when(bomMapper.countOrderReferences(any())).thenReturn(0L);
        when(bomItemMapper.selectCount(any())).thenReturn(0L);
        bomService.delete(targetId(target));
        verify(bomItemMapper).delete(any());
        verify(bomMapper).deleteById(any(Serializable.class));
    }

    private BomCreateCommand createCommand() {
        return new BomCreateCommand("M-P", "V1", LocalDate.of(2026, 1, 1), null, Boolean.FALSE, null,
                List.of(new BomItemCommand("M-C", new BigDecimal("2"), new BigDecimal("1.5"), "R1",
                        List.of("M-SUB"), Boolean.FALSE)));
    }

    private Bom bom(BomStatus status) {
        Bom bom = new Bom();
        bom.setId(UUID.randomUUID());
        bom.setParentMaterialId(UUID.randomUUID());
        bom.setBomVersion("V1");
        bom.setEffectiveDate(LocalDate.of(2026, 1, 1));
        bom.setStatus(status.name());
        bom.setIsPanel(Boolean.FALSE);
        bom.setVersion(1);
        return bom;
    }

    private Bom bom(UUID bomId, Material parent, BomStatus status) {
        Bom bom = new Bom();
        bom.setId(bomId);
        bom.setParentMaterialId((UUID) parent.getId());
        bom.setBomVersion("V1");
        bom.setEffectiveDate(LocalDate.of(2026, 1, 1));
        bom.setStatus(status.name());
        bom.setVersion(1);
        return bom;
    }

    private BomItem item(UUID bomId, UUID childMaterialId) {
        BomItem item = new BomItem();
        item.setId(UUID.randomUUID());
        item.setBomId(bomId);
        item.setChildMaterialId(childMaterialId);
        item.setQtyPerUnit(BigDecimal.ONE);
        item.setSeqNo(1);
        item.setLevel(1);
        return item;
    }

    private BomItem item(UUID bomId, Material child) {
        return item(bomId, (UUID) child.getId());
    }

    private Material material(String code) {
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode(code);
        material.setName(code + "-名称");
        material.setUnit("PCS");
        material.setVersion(1);
        return material;
    }

    private UUID targetId(Bom bom) {
        return (UUID) bom.getId();
    }

    private UUID firstUuid(LambdaQueryWrapper<?> wrapper) {
        wrapper.getSqlSegment();
        Optional<Object> candidate = wrapper.getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof UUID)
                .findFirst();
        return (UUID) candidate.orElse(null);
    }
}
