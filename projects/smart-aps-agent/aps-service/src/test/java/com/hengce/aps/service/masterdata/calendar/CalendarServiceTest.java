package com.hengce.aps.service.masterdata.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarCreateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarUpdateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.ShiftPayload;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * CalendarService 单测：覆盖同名版本号递增、历史日历 403、被引用删除拦截、
 * 班次重叠与分页 clamp。
 */
@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock
    private CalendarMapper calendarMapper;

    @Mock
    private ResourceMapper resourceMapper;

    @Mock
    private ResourceGroupMapper resourceGroupMapper;

    @InjectMocks
    private CalendarService calendarService;

    @Test
    void createShouldUseMaxPlusOneForSameName() {
        when(calendarMapper.maxCalendarVersion("测试日历")).thenReturn(1);
        ArgumentCaptor<Calendar> captor = ArgumentCaptor.forClass(Calendar.class);

        calendarService.create(createRequest("测试日历", 2, shift("08:00", "17:00", 1)));

        verify(calendarMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getCalendarVersion());
        assertEquals(1, captor.getValue().getVersion());
    }

    @Test
    void createShouldRejectCalendarVersionNotFollowingMaxPlusOne() {
        when(calendarMapper.maxCalendarVersion("测试日历")).thenReturn(2);

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.create(createRequest("测试日历", 1, shift("08:00", "17:00", 1))));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void updateShouldRejectHistoricalCalendarWithForbidden() {
        UUID id = UUID.randomUUID();
        Calendar existing = entity(id, LocalDate.now(ZoneOffset.UTC).minusDays(1));
        when(calendarMapper.findById(id)).thenReturn(existing);

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.update(id, updateRequest(1, "改名后的日历")));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void updateShouldRejectStaleVersionWithConflict() {
        UUID id = UUID.randomUUID();
        Calendar existing = entity(id, null);
        existing.setVersion(2);
        when(calendarMapper.findById(id)).thenReturn(existing);

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.update(id, updateRequest(1, null)));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void deleteShouldRejectCalendarReferencedByResourceWithUnprocessableEntity() {
        UUID id = UUID.randomUUID();
        when(calendarMapper.findById(id)).thenReturn(entity(id, null));
        when(calendarMapper.countResourceReferences(id)).thenReturn(2L);
        when(calendarMapper.countResourceGroupReferences(id)).thenReturn(0L);

        BizException exception = assertThrows(BizException.class, () -> calendarService.delete(id));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("2 个资源"));
    }

    @Test
    void createShouldRejectOverlappingShifts() {
        BizException exception = assertThrows(BizException.class,
                () -> calendarService.create(createRequest("测试日历", 1,
                        shift("08:00", "12:00", 1), shift("10:00", "14:00", 1))));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
    }

    @Test
    void createShouldRejectPastEffectiveFromForResourceCalendarWithFieldMessage() {
        CalendarCreateRequest request = new CalendarCreateRequest("资源日历", 1, "RESOURCE", null, null,
                LocalDate.now(ZoneOffset.UTC).minusDays(1), null,
                List.of(shift("08:00", "17:00", 1)), List.of());

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.create(request));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(40000, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("RESOURCE 类型日历的 effectiveFrom 不能早于今天"));
        verify(calendarMapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createShouldRejectDeletedResourceReferenceWithUnprocessableEntity() {
        UUID resourceId = UUID.randomUUID();
        when(resourceMapper.selectById(resourceId)).thenReturn(null);
        CalendarCreateRequest request = new CalendarCreateRequest("资源日历", 1, "RESOURCE", resourceId, null,
                LocalDate.now(ZoneOffset.UTC), null,
                List.of(shift("08:00", "17:00", 1)), List.of());

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.create(request));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("引用的资源不存在或已删除: " + resourceId));
    }

    @Test
    void updateShouldRejectDeletedResourceReferenceWithUnprocessableEntity() {
        UUID id = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Calendar existing = entity(id, null);
        existing.setType("RESOURCE");
        when(calendarMapper.findById(id)).thenReturn(existing);
        when(resourceMapper.selectById(resourceId)).thenReturn(null);
        CalendarUpdateRequest request = new CalendarUpdateRequest(1, null, null, resourceId, null,
                null, null, null, null);

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.update(id, request));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("引用的资源不存在或已删除: " + resourceId));
    }

    @Test
    void createShouldMapForeignKeyViolationToUnprocessableEntity() {
        UUID resourceId = UUID.randomUUID();
        when(resourceMapper.selectById(resourceId)).thenReturn(new Resource());
        when(calendarMapper.insert(org.mockito.ArgumentMatchers.any())).thenThrow(
                new DataIntegrityViolationException("fk_calendar_resource",
                        new SQLException("violates fk_calendar_resource", "23503")));
        CalendarCreateRequest request = new CalendarCreateRequest("资源日历", 1, "RESOURCE", resourceId, null,
                LocalDate.now(ZoneOffset.UTC), null,
                List.of(shift("08:00", "17:00", 1)), List.of());

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.create(request));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("引用的资源不存在或已删除: " + resourceId));
    }

    @Test
    void listShouldClampPageSizeToMaximum() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setSize(999);

        calendarService.list(null, null, null, null, query);

        ArgumentCaptor<Page<Calendar>> captor = ArgumentCaptor.forClass(Page.class);
        verify(calendarMapper).selectPage(captor.capture(), isNull(), isNull(), isNull(), isNull());
        assertEquals(1L, captor.getValue().getCurrent());
        assertEquals(ApsConstants.MAX_PAGE_SIZE, captor.getValue().getSize());
    }

    private CalendarCreateRequest createRequest(String name, int calendarVersion, ShiftPayload... shifts) {
        return new CalendarCreateRequest(name, calendarVersion, "FACTORY", null, null,
                LocalDate.now(ZoneOffset.UTC).plusDays(1), null, List.of(shifts), List.of());
    }

    private CalendarUpdateRequest updateRequest(int version, String name) {
        return new CalendarUpdateRequest(version, name, null, null, null, null, null, null, null);
    }

    private ShiftPayload shift(String startTime, String endTime, Integer... days) {
        return new ShiftPayload(startTime, endTime, List.of(days));
    }

    private Calendar entity(UUID id, LocalDate effectiveTo) {
        Calendar entity = new Calendar();
        entity.setId(id);
        entity.setName("测试日历");
        entity.setType("FACTORY");
        entity.setCalendarVersion(1);
        entity.setEffectiveFrom(LocalDate.now(ZoneOffset.UTC).minusYears(1));
        entity.setEffectiveTo(effectiveTo);
        entity.setVersion(1);
        entity.setDeleted(false);
        return entity;
    }
}
