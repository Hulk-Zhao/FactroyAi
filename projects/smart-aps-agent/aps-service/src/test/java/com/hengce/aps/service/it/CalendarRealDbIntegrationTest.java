package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarUpdateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.ShiftPayload;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 日历真库集成测试：历史只读 403、班次重叠 422、calendar_version 递增。
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class CalendarRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Test
    void historicalCalendarUpdateRejectedWithForbidden() {
        String name = tag("CAL-HISTORY");
        CalendarResponse calendar = createCalendar(name, 1, LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), "FACTORY", null, List.of(shift("08:00", "17:00")));

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.update(calendar.id(), new CalendarUpdateRequest(calendar.version(),
                        name + "-RENAMED", null, null, null, null, null, null, null)));

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, version, effective_to FROM calendar WHERE id = ?", calendar.id());
        assertEquals(name, row.get("name"), "403 后数据库内容必须保持不变");
        assertEquals(1, ((Number) row.get("version")).intValue());
        evidence("UPDATE 历史日历(id=" + calendar.id() + ", effectiveTo=2025-12-31) -> FORBIDDEN: "
                + exception.getMessage());
        evidence("SQL=SELECT name,version,effective_to FROM calendar WHERE id='" + calendar.id()
                + "' -> " + row);
    }

    @Test
    void overlappingShiftsRejectedWithoutPersistingRow() {
        String name = tag("CAL-OVERLAP");
        List<ShiftPayload> shifts = List.of(shift("08:00", "12:00"), shift("11:00", "13:00"));

        BizException exception = assertThrows(BizException.class,
                () -> createCalendar(name, 1, LocalDate.now(ZoneOffset.UTC), null, "FACTORY", null,
                        shifts));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("班次时段重叠"), exception.getMessage());
        long rows = count("SELECT count(*) FROM calendar WHERE name = ?", name);
        assertEquals(0L, rows, "重叠班次被拒绝后不得落库");
        evidence("CREATE 重叠班次(08:00-12:00, 11:00-13:00) -> 422: " + exception.getMessage());
        evidence("SQL=SELECT count(*) FROM calendar WHERE name='" + name + "' -> " + rows);
    }

    @Test
    void resourceCalendarReferencingDeletedResourceRejectedWith422() {
        String resourceCode = tag("RES-CAL-DEL");
        ResourceView resource = createResource(resourceCode, CapacityMode.SECONDS_PER_UNIT,
                BigDecimal.TEN, null);
        resourceService.delete(resourceCode);
        String name = tag("CAL-RES-DEL");

        BizException exception = assertThrows(BizException.class,
                () -> createCalendar(name, 1, LocalDate.now(ZoneOffset.UTC), null, "RESOURCE",
                        resource.id(), List.of(shift("08:00", "17:00"))));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("引用的资源不存在或已删除: " + resource.id()),
                exception.getMessage());
        long rows = count("SELECT count(*) FROM calendar WHERE name = ?", name);
        assertEquals(0L, rows, "引用软删资源被拒绝后不得落库");
        evidence("CREATE RESOURCE 日历引用软删资源 id=" + resource.id() + " -> 422: "
                + exception.getMessage());
        evidence("SQL=SELECT count(*) FROM calendar WHERE name='" + name + "' -> " + rows);
    }

    @Test
    void resourceCalendarWithPastEffectiveFromRejectedWithFieldMessage() {
        String name = tag("CAL-PAST");

        BizException exception = assertThrows(BizException.class,
                () -> createCalendar(name, 1, LocalDate.of(2026, 1, 1), null, "RESOURCE", null,
                        List.of(shift("08:00", "17:00"))));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(40000, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("RESOURCE 类型日历的 effectiveFrom 不能早于今天"),
                exception.getMessage());
        assertEquals(0L, count("SELECT count(*) FROM calendar WHERE name = ?", name));
        evidence("CREATE RESOURCE 日历 effectiveFrom=2026-01-01 -> 400: " + exception.getMessage());
    }

    @Test
    void calendarVersionIncrementsPerName() {
        String name = tag("CAL-VERSION");
        LocalDate from = LocalDate.now(ZoneOffset.UTC);
        CalendarResponse version1 = createCalendar(name, 1, from, null, "FACTORY", null,
                List.of(shift("08:00", "17:00")));
        CalendarResponse version2 = createCalendar(name, 2, from, null, "FACTORY", null,
                List.of(shift("06:00", "14:00")));

        List<Integer> versions = jdbc.queryForList(
                "SELECT calendar_version FROM calendar WHERE name = ? ORDER BY calendar_version",
                Integer.class, name);
        assertEquals(List.of(1, 2), versions);
        assertEquals(1, version1.version());
        assertEquals(1, version2.version());
        evidence("SQL=SELECT calendar_version FROM calendar WHERE name='" + name
                + "' ORDER BY calendar_version -> " + versions);
    }
}
