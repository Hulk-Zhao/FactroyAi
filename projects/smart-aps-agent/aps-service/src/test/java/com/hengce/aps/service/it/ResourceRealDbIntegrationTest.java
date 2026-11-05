package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.service.masterdata.calendar.dto.ShiftPayload;
import com.hengce.aps.service.masterdata.resource.ResourceCreateCommand;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 资源真库集成测试：编码唯一、被日历引用删除 422、三种 capacity_mode 落库。
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class ResourceRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Test
    void duplicateResourceCodeRejectedByDatabase() {
        String code = tag("RES-DUP");
        createResource(code, CapacityMode.UNIT_PER_HOUR, BigDecimal.TEN, null);

        BizException exception = assertThrows(BizException.class,
                () -> createResource(code, CapacityMode.UNIT_PER_HOUR, BigDecimal.TEN, null));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        long rows = count("SELECT count(*) FROM resource WHERE code = ? AND deleted = false", code);
        assertEquals(1L, rows);
        evidence("CREATE 重复资源编码 code='" + code + "' -> " + exception.getErrorCode()
                + ": " + exception.getMessage());
        evidence("SQL=SELECT count(*) FROM resource WHERE code='" + code
                + "' AND deleted=false -> " + rows);
    }

    @Test
    void deleteResourceReferencedByCalendarRejectedWith422() {
        String code = tag("RES-REF");
        ResourceView resource = createResource(code, CapacityMode.UNIT_PER_HOUR, BigDecimal.TEN, null);
        String calendarName = tag("CAL-RES-REF");
        createCalendar(calendarName, 1, LocalDate.now(ZoneOffset.UTC), null, "RESOURCE",
                resource.id(), List.of(shift("08:00", "17:00")));

        BizException exception = assertThrows(BizException.class,
                () -> resourceService.delete(code));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT deleted FROM resource WHERE code = ?", code);
        assertEquals(false, row.get("deleted"), "422 后资源不得被软删除");
        evidence("DELETE 被日历(id=" + calendarName + ")引用的资源 code='" + code + "' -> 422: "
                + exception.getMessage());
        evidence("SQL=SELECT deleted FROM resource WHERE code='" + code + "' -> " + row);
    }

    @Test
    void unknownResourceGroupRejectedWith422BeforeForeignKey() {
        String code = tag("RES-BAD-GROUP");
        UUID groupId = UUID.randomUUID();
        ResourceCreateCommand command = new ResourceCreateCommand(code, "坏资源组", ResourceType.PRIMARY,
                groupId, CapacityMode.UNIT_PER_HOUR, BigDecimal.TEN, null, BigDecimal.ONE,
                null, 0, 0, null);

        BizException exception = assertThrows(BizException.class,
                () -> resourceService.create(command));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("资源组不存在: " + groupId), exception.getMessage());
        assertEquals(0L, count("SELECT count(*) FROM resource WHERE code = ?", code));
        evidence("CREATE 资源 groupId=" + groupId + " 不存在 -> 422: " + exception.getMessage());
    }

    @Test
    void unknownCalendarRejectedWith422BeforeForeignKey() {
        String code = tag("RES-BAD-CAL");
        UUID calendarId = UUID.randomUUID();
        ResourceCreateCommand command = new ResourceCreateCommand(code, "坏日历", ResourceType.PRIMARY,
                null, CapacityMode.UNIT_PER_HOUR, BigDecimal.TEN, null, BigDecimal.ONE,
                calendarId, 0, 0, null);

        BizException exception = assertThrows(BizException.class,
                () -> resourceService.create(command));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("日历不存在或已删除: " + calendarId),
                exception.getMessage());
        assertEquals(0L, count("SELECT count(*) FROM resource WHERE code = ?", code));
        evidence("CREATE 资源 calendarId=" + calendarId + " 不存在 -> 422: " + exception.getMessage());
    }

    @Test
    void allCapacityModesPersisted() {
        String unitPerHour = tag("RES-UPH");
        String secondsPerUnit = tag("RES-SPU");
        String batchSeconds = tag("RES-BATCH");
        createResource(unitPerHour, CapacityMode.UNIT_PER_HOUR, new BigDecimal("10"), null);
        createResource(secondsPerUnit, CapacityMode.SECONDS_PER_UNIT, new BigDecimal("30"), null);
        createResource(batchSeconds, CapacityMode.BATCH_SECONDS, new BigDecimal("100"), 5);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT code, capacity_mode, capacity_value, batch_capacity FROM resource "
                        + "WHERE code LIKE ? ORDER BY code", RUN_PREFIX + "%");
        assertEquals(3, rows.size());
        Map<String, Object> uph = rows.stream()
                .filter(row -> unitPerHour.equals(row.get("code"))).findFirst().orElseThrow();
        Map<String, Object> spu = rows.stream()
                .filter(row -> secondsPerUnit.equals(row.get("code"))).findFirst().orElseThrow();
        Map<String, Object> batch = rows.stream()
                .filter(row -> batchSeconds.equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals("UNIT_PER_HOUR", uph.get("capacity_mode"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) uph.get("capacity_value")));
        assertEquals("SECONDS_PER_UNIT", spu.get("capacity_mode"));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) spu.get("capacity_value")));
        assertEquals("BATCH_SECONDS", batch.get("capacity_mode"));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) batch.get("capacity_value")));
        assertEquals(5, ((Number) batch.get("batch_capacity")).intValue());
        evidence("SQL=SELECT code,capacity_mode,capacity_value,batch_capacity FROM resource "
                + "WHERE code LIKE '" + RUN_PREFIX + "%' -> " + rows);
    }

    @Test
    void capacityValueDefaultsToZeroWhenOmitted() {
        String code = tag("RES-DEFAULT");
        ResourceView resource = createResource(code, CapacityMode.SECONDS_PER_UNIT, null, null);

        assertEquals(0, BigDecimal.ZERO.compareTo(resource.capacityValue()));
        evidence("CREATE SECONDS_PER_UNIT 未传 capacityValue -> 落库值 " + resource.capacityValue());
    }
}
