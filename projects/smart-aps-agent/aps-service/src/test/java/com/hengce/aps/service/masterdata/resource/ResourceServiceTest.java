package com.hengce.aps.service.masterdata.resource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroup;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

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
 * 资源服务单元测试：编码唯一性、版本冲突、引用删除、幂等停用、C10 筛选与 C2 产能校验。
 *
 * <p>全部使用 Mockito，不依赖真实数据库。</p>
 */
class ResourceServiceTest {

    private ResourceMapper resourceMapper;

    private ResourceGroupMapper resourceGroupMapper;

    private CalendarMapper calendarMapper;

    private ResourceService resourceService;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存，测试环境需手动初始化
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
        TableInfoHelper.initTableInfo(assistant, Resource.class);
        resourceMapper = mock(ResourceMapper.class);
        resourceGroupMapper = mock(ResourceGroupMapper.class);
        calendarMapper = mock(CalendarMapper.class);
        resourceService = new ResourceService(resourceMapper, resourceGroupMapper, calendarMapper);
    }

    @Test
    void createShouldRejectDuplicateCodeWithConflict() {
        when(resourceMapper.selectCount(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class,
                () -> resourceService.create(createCommand("R-001")));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        verify(resourceMapper, never()).insert(any(Resource.class));
    }

    @Test
    void createShouldGenerateUuidAndApplyCapacityDefaults() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        ResourceCreateCommand command = new ResourceCreateCommand("R-001", "CNC-01", ResourceType.PRIMARY,
                null, CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null, null,
                null, null, null, null);
        ResourceView view = resourceService.create(command);
        ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
        verify(resourceMapper).insert(captor.capture());
        Resource inserted = captor.getValue();
        assertTrue(inserted.getId() instanceof UUID);
        assertEquals(view.id(), inserted.getId());
        assertEquals(0, BigDecimal.ONE.compareTo(inserted.getEfficiency()));
        assertEquals(0, Integer.valueOf(0).compareTo(inserted.getSetupSeconds()));
        assertEquals(0, Integer.valueOf(0).compareTo(inserted.getTeardownSeconds()));
        assertEquals("R-001", view.code());
        assertNotNull(inserted.getMaintenancePlan());
        assertNotNull(inserted.getIndustryAttrs());
    }

    @Test
    void createShouldDefaultBatchCapacityForBatchMode() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        ResourceCreateCommand command = new ResourceCreateCommand("R-002", "烤炉", ResourceType.SECONDARY,
                null, CapacityMode.BATCH_SECONDS, new BigDecimal("120"), null, null,
                null, null, null, null);
        resourceService.create(command);
        ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
        verify(resourceMapper).insert(captor.capture());
        assertEquals(Integer.valueOf(1), captor.getValue().getBatchCapacity());
    }

    @Test
    void createShouldRejectNegativeCapacity() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        ResourceCreateCommand command = new ResourceCreateCommand("R-003", "CNC-03", ResourceType.PRIMARY,
                null, CapacityMode.UNIT_PER_HOUR, new BigDecimal("-1"), null, null,
                null, null, null, null);
        BizException exception = assertThrows(BizException.class, () -> resourceService.create(command));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(resourceMapper, never()).insert(any(Resource.class));
    }

    @Test
    void createShouldRejectNullCapacityForUnitPerHour() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        ResourceCreateCommand command = new ResourceCreateCommand("R-004", "CNC-04", ResourceType.PRIMARY,
                null, CapacityMode.UNIT_PER_HOUR, null, null, null,
                null, null, null, null);
        BizException exception = assertThrows(BizException.class, () -> resourceService.create(command));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
    }

    @Test
    void createShouldRejectEfficiencyOutOfRange() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        ResourceCreateCommand command = new ResourceCreateCommand("R-005", "CNC-05", ResourceType.PRIMARY,
                null, CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null, new BigDecimal("1.6"),
                null, null, null, null);
        BizException exception = assertThrows(BizException.class, () -> resourceService.create(command));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
    }

    @Test
    void createShouldRejectUnknownResourceGroup() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        when(resourceGroupMapper.selectById(any())).thenReturn(null);
        ResourceCreateCommand command = new ResourceCreateCommand("R-006", "CNC-06", ResourceType.PRIMARY,
                UUID.randomUUID(), CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null, null,
                null, null, null, null);
        BizException exception = assertThrows(BizException.class, () -> resourceService.create(command));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(resourceMapper, never()).insert(any(Resource.class));
    }

    @Test
    void createShouldRejectUnknownCalendar() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        when(calendarMapper.findById(any())).thenReturn(null);
        UUID calendarId = UUID.randomUUID();
        ResourceCreateCommand command = new ResourceCreateCommand("R-007", "CNC-07", ResourceType.PRIMARY,
                null, CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null, null,
                calendarId, null, null, null);

        BizException exception = assertThrows(BizException.class, () -> resourceService.create(command));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("日历不存在或已删除: " + calendarId));
        verify(resourceMapper, never()).insert(any(Resource.class));
    }

    @Test
    void createShouldMapGroupForeignKeyViolationToUnprocessableEntity() {
        when(resourceMapper.selectCount(any())).thenReturn(0L);
        when(resourceGroupMapper.selectById(any())).thenReturn(new ResourceGroup());
        when(resourceMapper.insert(any(Resource.class))).thenThrow(
                new DataIntegrityViolationException("fk_resource_group",
                        new SQLException("violates fk_resource_group", "23503")));
        UUID groupId = UUID.randomUUID();
        ResourceCreateCommand command = new ResourceCreateCommand("R-008", "CNC-08", ResourceType.PRIMARY,
                groupId, CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null, null,
                null, null, null, null);

        BizException exception = assertThrows(BizException.class, () -> resourceService.create(command));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("资源组不存在: " + groupId));
    }

    @Test
    void updateShouldMapCalendarForeignKeyViolationToUnprocessableEntity() {
        Resource existing = sampleResource();
        when(resourceMapper.selectOne(any())).thenReturn(existing);
        when(calendarMapper.findById(any())).thenReturn(new Calendar());
        when(resourceMapper.updateById(any(Resource.class))).thenThrow(
                new DataIntegrityViolationException("fk_resource_calendar",
                        new SQLException("violates fk_resource_calendar", "23503")));
        UUID calendarId = UUID.randomUUID();
        ResourceUpdateCommand command = new ResourceUpdateCommand(null, null, null,
                null, null, null, null, calendarId, null, null, null, 1);

        BizException exception = assertThrows(BizException.class,
                () -> resourceService.update("R-001", command));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("日历不存在或已删除: " + calendarId));
    }

    @Test
    void getShouldReportNotFound() {
        when(resourceMapper.selectOne(any())).thenReturn(null);
        BizException exception = assertThrows(BizException.class, () -> resourceService.get("R-404"));
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void updateShouldRejectVersionConflict() {
        Resource existing = sampleResource();
        existing.setVersion(2);
        when(resourceMapper.selectOne(any())).thenReturn(existing);
        BizException exception = assertThrows(BizException.class,
                () -> resourceService.update("R-001", updateCommand(1)));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        verify(resourceMapper, never()).updateById(any(Resource.class));
    }

    @Test
    void updateShouldRejectInvalidMergedCapacity() {
        Resource existing = sampleResource();
        when(resourceMapper.selectOne(any())).thenReturn(existing);
        ResourceUpdateCommand command = new ResourceUpdateCommand(null, null, null,
                CapacityMode.BATCH_SECONDS, BigDecimal.ZERO, null, null,
                null, null, null, null, 1);
        BizException exception = assertThrows(BizException.class,
                () -> resourceService.update("R-001", command));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(resourceMapper, never()).updateById(any(Resource.class));
    }

    @Test
    void deleteShouldBeBlockedByCalendarReference() {
        Resource existing = sampleResource();
        when(resourceMapper.selectOne(any())).thenReturn(existing);
        when(resourceMapper.countCalendarReferences(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class, () -> resourceService.delete("R-001"));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("日历"));
        verify(resourceMapper, never()).deleteById(any());
    }

    @Test
    void deleteShouldBeBlockedByTaskReference() {
        Resource existing = sampleResource();
        when(resourceMapper.selectOne(any())).thenReturn(existing);
        when(resourceMapper.countTaskReferences(any())).thenReturn(1L);
        BizException exception = assertThrows(BizException.class, () -> resourceService.delete("R-001"));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("排产任务"));
        verify(resourceMapper, never()).deleteById(any());
    }

    @Test
    void deleteShouldLogicallyDeleteWhenNotReferenced() {
        Resource existing = sampleResource();
        when(resourceMapper.selectOne(any())).thenReturn(existing);
        resourceService.delete("R-001");
        verify(resourceMapper).deleteById(existing.getId());
    }

    @Test
    void deleteShouldBeIdempotentWhenAlreadyDeleted() {
        when(resourceMapper.selectOne(any())).thenReturn(null);
        when(resourceMapper.countDeletedByCode("R-001")).thenReturn(1L);
        resourceService.delete("R-001");
        verify(resourceMapper, never()).deleteById(any());
    }

    @Test
    void deleteShouldReportNotFoundWhenAbsent() {
        when(resourceMapper.selectOne(any())).thenReturn(null);
        when(resourceMapper.countDeletedByCode("R-404")).thenReturn(0L);
        BizException exception = assertThrows(BizException.class, () -> resourceService.delete("R-404"));
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void listShouldClampPageAndSize() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setSize(1000);
        when(resourceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<Resource> page = invocation.getArgument(0);
            page.setRecords(List.of(sampleResource()));
            page.setTotal(1L);
            return page;
        });
        PageResult<ResourceView> result = resourceService.list(query, null, null, null, null);
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(resourceMapper).selectPage(captor.capture(), any());
        assertEquals(1L, captor.getValue().getCurrent());
        assertEquals(200L, captor.getValue().getSize());
        assertEquals(1, result.items().size());
    }

    /**
     * C10：code 与 name 之间 OR（模糊、忽略大小写），与 type/groupId 之间 AND。
     */
    @Test
    void listWrapperShouldCombineCodeNameByOrAndTypeGroupByAnd() {
        UUID groupId = UUID.randomUUID();
        LambdaQueryWrapper<Resource> wrapper = resourceService.buildListWrapper(
                "CNC", "车床", ResourceType.PRIMARY, groupId);
        String compact = wrapper.getSqlSegment().replaceAll("\\s+", " ").trim();
        assertTrue(compact.contains("LOWER(code) LIKE LOWER(CONCAT('%',"), compact);
        assertTrue(compact.contains("LOWER(name) LIKE LOWER(CONCAT('%',"), compact);
        assertTrue(compact.contains(" OR "), compact);
        assertTrue(compact.contains(" AND "), compact);
        assertTrue(compact.contains("type ="), compact);
        assertTrue(compact.contains("group_id ="), compact);
    }

    @Test
    void listWrapperShouldEscapeLikeWildcards() {
        LambdaQueryWrapper<Resource> wrapper = resourceService.buildListWrapper("50%_off", null, null, null);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("LOWER(code) LIKE"), sql);
        assertEquals(1, wrapper.getParamNameValuePairs().size());
        Object value = wrapper.getParamNameValuePairs().values().iterator().next();
        assertEquals("50\\%\\_off", value);
    }

    @Test
    void listWrapperShouldIgnoreBlankKeywords() {
        LambdaQueryWrapper<Resource> wrapper = resourceService.buildListWrapper("   ", "", null, null);
        assertTrue(wrapper.getSqlSegment().isBlank());
        assertFalse(wrapper.getParamNameValuePairs().containsValue(""));
    }

    private Resource sampleResource() {
        Resource resource = new Resource();
        resource.setId(UUID.randomUUID());
        resource.setCode("R-001");
        resource.setName("CNC-01");
        resource.setType(ResourceType.PRIMARY);
        resource.setCapacityMode(CapacityMode.UNIT_PER_HOUR);
        resource.setCapacityValue(new BigDecimal("10"));
        resource.setEfficiency(BigDecimal.ONE);
        resource.setSetupSeconds(0);
        resource.setTeardownSeconds(0);
        resource.setVersion(1);
        return resource;
    }

    private ResourceCreateCommand createCommand(String code) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("brand", "FANUC");
        return new ResourceCreateCommand(code, "CNC-01", ResourceType.PRIMARY, null,
                CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null, null,
                null, null, null, attrs);
    }

    private ResourceUpdateCommand updateCommand(Integer version) {
        return new ResourceUpdateCommand(null, null, null, null, null, null, null,
                null, null, null, null, version);
    }
}
