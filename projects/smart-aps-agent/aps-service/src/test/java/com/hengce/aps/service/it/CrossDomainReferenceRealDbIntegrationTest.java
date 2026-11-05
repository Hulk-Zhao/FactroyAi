package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.service.masterdata.bom.BomView;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.resource.ResourceCreateCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 跨域引用完整性：删除被引用记录必须被拦截，并核对数据库未被软删除。
 *
 * <p>注意 {@link #calendarReferencedByResourceRejectedWith422()}：
 * 该用例原登记缺陷 D-01（当时日历删除返回 40901/409），当前实现已按任务卡返回 422，
 * 断言同步更新为 42200。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class CrossDomainReferenceRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Test
    void materialReferencedAsBomParentRejected() {
        String parent = tag("MAT-XR-PARENT");
        String child = tag("MAT-XR-CHILD");
        createMaterial(parent);
        createMaterial(child);
        createBom(parent, "V1", child);

        BizException exception = assertThrows(BizException.class,
                () -> materialService.delete(parent));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(false, jdbc.queryForMap("SELECT deleted FROM material WHERE code = ?", parent)
                .get("deleted"));
        evidence("DELETE 被 BOM 父件引用的物料 code='" + parent + "' -> 422: " + exception.getMessage());
    }

    @Test
    void materialReferencedAsBomChildRejected() {
        String parent = tag("MAT-XR-P2");
        String child = tag("MAT-XR-C2");
        createMaterial(parent);
        createMaterial(child);
        createBom(parent, "V1", child);

        BizException exception = assertThrows(BizException.class,
                () -> materialService.delete(child));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(false, jdbc.queryForMap("SELECT deleted FROM material WHERE code = ?", child)
                .get("deleted"));
        evidence("DELETE 被 BOM 子件引用的物料 code='" + child + "' -> 422: " + exception.getMessage());
    }

    @Test
    void bomReferencedByOtherBomRejected() {
        String root = tag("MAT-BR-A");
        String middle = tag("MAT-BR-B");
        String leaf = tag("MAT-BR-C");
        createMaterial(root);
        createMaterial(middle);
        createMaterial(leaf);
        BomView middleBom = createBom(middle, "V1", leaf);
        createBom(root, "V1", middle);

        BizException exception = assertThrows(BizException.class,
                () -> bomService.delete(middleBom.id()));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        Map<String, Object> row = jdbc.queryForMap("SELECT deleted FROM bom WHERE id = ?",
                middleBom.id());
        assertEquals(false, row.get("deleted"));
        evidence("DELETE 被其它 BOM 作为子件引用的 BOM(id=" + middleBom.id() + ") -> 422: "
                + exception.getMessage());
    }

    @Test
    void calendarReferencedByResourceRejectedWith422() {
        String calendarName = tag("CAL-XREF");
        CalendarResponse calendar = createCalendar(calendarName, 1,
                LocalDate.now(ZoneOffset.UTC), null, "FACTORY", null,
                List.of(shift("08:00", "17:00")));
        String resourceCode = tag("RES-XREF");
        resourceService.create(new ResourceCreateCommand(resourceCode, "测试资源 " + resourceCode,
                ResourceType.PRIMARY, null, CapacityMode.UNIT_PER_HOUR, BigDecimal.TEN, null,
                BigDecimal.ONE, calendar.id(), 0, 0, null));

        BizException exception = assertThrows(BizException.class,
                () -> calendarService.delete(calendar.id()));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
        assertEquals(false, jdbc.queryForMap("SELECT deleted FROM calendar WHERE id = ?", calendar.id())
                .get("deleted"));
        evidence("DELETE 被资源 code='" + resourceCode + "'引用的日历(name='" + calendarName
                + "') -> " + exception.getErrorCode() + "(" + exception.getMessage() + ")");
    }
}
