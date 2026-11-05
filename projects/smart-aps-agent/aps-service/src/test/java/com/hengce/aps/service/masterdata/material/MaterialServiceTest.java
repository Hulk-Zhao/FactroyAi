package com.hengce.aps.service.masterdata.material;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * 物料服务单元测试：编码唯一性、软删除、引用删除拦截、分页 clamp 与 C10 筛选语义。
 *
 * <p>全部使用 Mockito，不依赖真实数据库。</p>
 */
class MaterialServiceTest {

    private MaterialMapper materialMapper;

    private MaterialService materialService;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存，测试环境需手动初始化
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        TableInfoHelper.initTableInfo(assistant, Material.class);
        materialMapper = mock(MaterialMapper.class);
        materialService = new MaterialService(materialMapper);
    }

    @Test
    void createShouldRejectDuplicateCodeWithConflict() {
        when(materialMapper.selectCount(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class,
                () -> materialService.create(createCommand("M-001")));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, exception.getErrorCode());
        assertEquals(40900, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("物料编码已存在"));
        verify(materialMapper, never()).insert(any(Material.class));
    }

    @Test
    void createShouldGenerateUuidAndInsert() {
        when(materialMapper.selectCount(any())).thenReturn(0L);
        MaterialView view = materialService.create(createCommand("M-001"));
        ArgumentCaptor<Material> captor = ArgumentCaptor.forClass(Material.class);
        verify(materialMapper).insert(captor.capture());
        Material inserted = captor.getValue();
        assertTrue(inserted.getId() instanceof UUID);
        assertEquals("M-001", view.code());
        assertNotNull(inserted.getExtAttrs());
        assertNotNull(inserted.getIndustryAttrs());
    }

    @Test
    void getShouldReportNotFound() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        BizException exception = assertThrows(BizException.class, () -> materialService.get("M-404"));
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void updateShouldRejectVersionConflict() {
        Material existing = sampleMaterial();
        existing.setVersion(2);
        when(materialMapper.selectOne(any())).thenReturn(existing);
        BizException exception = assertThrows(BizException.class,
                () -> materialService.update("M-001", updateCommand(1)));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        verify(materialMapper, never()).updateById(any(Material.class));
    }

    @Test
    void deleteShouldLogicallyDeleteWhenNotReferenced() {
        Material existing = sampleMaterial();
        when(materialMapper.selectOne(any())).thenReturn(existing);
        materialService.delete("M-001");
        verify(materialMapper).deleteById(existing.getId());
    }

    @Test
    void deleteShouldBeBlockedByBomReference() {
        Material existing = sampleMaterial();
        when(materialMapper.selectOne(any())).thenReturn(existing);
        when(materialMapper.countBomReferences(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class, () -> materialService.delete("M-001"));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("BOM"));
        verify(materialMapper, never()).deleteById(any());
    }

    @Test
    void deleteShouldBeBlockedByRoutingReference() {
        Material existing = sampleMaterial();
        when(materialMapper.selectOne(any())).thenReturn(existing);
        when(materialMapper.countRoutingReferences(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class, () -> materialService.delete("M-001"));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("工艺路线"));
        verify(materialMapper, never()).deleteById(any());
    }

    @Test
    void listShouldClampPageAndSize() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setSize(1000);
        when(materialMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<Material> page = invocation.getArgument(0);
            page.setRecords(List.of(sampleMaterial()));
            page.setTotal(1L);
            return page;
        });
        PageResult<MaterialView> result = materialService.list(query, null, null, null, null);
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(materialMapper).selectPage(captor.capture(), any());
        assertEquals(1L, captor.getValue().getCurrent());
        assertEquals(200L, captor.getValue().getSize());
        assertEquals(1, result.page());
        assertEquals(200, result.size());
        assertEquals(1, result.items().size());
    }

    /**
     * C10：code 与 name 之间 OR（模糊、忽略大小写），与 type/categoryId 之间 AND。
     */
    @Test
    void listWrapperShouldCombineCodeNameByOrAndTypeByAnd() {
        UUID categoryId = UUID.randomUUID();
        LambdaQueryWrapper<Material> wrapper = materialService.buildListWrapper(
                "Fin", "壳体", MaterialType.FINISHED, categoryId);
        String compact = wrapper.getSqlSegment().replaceAll("\\s+", " ").trim();
        assertTrue(compact.contains("LOWER(code) LIKE LOWER(CONCAT('%',"));
        assertTrue(compact.contains("LOWER(name) LIKE LOWER(CONCAT('%',"));
        assertTrue(compact.contains(" OR "), compact);
        assertTrue(compact.contains(" AND "), compact);
        assertTrue(compact.contains("type ="), compact);
        assertTrue(compact.contains("category_id ="), compact);
    }

    @Test
    void listWrapperShouldEscapeLikeWildcards() {
        LambdaQueryWrapper<Material> wrapper = materialService.buildListWrapper("50%_off", null, null, null);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("LOWER(code) LIKE"), sql);
        assertEquals(1, wrapper.getParamNameValuePairs().size());
        Object value = wrapper.getParamNameValuePairs().values().iterator().next();
        assertEquals("50\\%\\_off", value);
    }

    @Test
    void listWrapperShouldIgnoreBlankKeywords() {
        LambdaQueryWrapper<Material> wrapper = materialService.buildListWrapper("   ", "", null, null);
        assertTrue(wrapper.getSqlSegment().isBlank());
        assertFalse(wrapper.getParamNameValuePairs().containsValue(""));
    }

    private Material sampleMaterial() {
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode("M-001");
        material.setName("电机壳体");
        material.setType(MaterialType.RAW);
        material.setUnit("PCS");
        material.setVersion(1);
        return material;
    }

    private MaterialCreateCommand createCommand(String code) {
        Map<String, Object> extAttrs = new LinkedHashMap<>();
        extAttrs.put("color", "black");
        return new MaterialCreateCommand(code, "电机壳体", MaterialType.FINISHED, "100x80",
                "PCS", UUID.randomUUID(), null, null, 3, null, extAttrs, null);
    }

    private MaterialUpdateCommand updateCommand(Integer version) {
        return new MaterialUpdateCommand("M-001", "电机壳体", null, null, null, null,
                null, null, null, null, null, null, version);
    }
}
