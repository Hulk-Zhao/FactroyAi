package com.hengce.aps.service.it;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.service.masterdata.bom.BomCreateCommand;
import com.hengce.aps.service.masterdata.bom.BomItemCommand;
import com.hengce.aps.service.masterdata.bom.BomService;
import com.hengce.aps.service.masterdata.bom.BomView;
import com.hengce.aps.service.masterdata.calendar.CalendarService;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarCreateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.calendar.dto.ShiftPayload;
import com.hengce.aps.service.masterdata.material.MaterialCreateCommand;
import com.hengce.aps.service.masterdata.material.MaterialService;
import com.hengce.aps.service.masterdata.resource.ResourceCreateCommand;
import com.hengce.aps.service.masterdata.resource.ResourceService;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真库集成测试基座：生产同款 Spring 装配 + 按前缀清理测试数据 + 证据记录。
 *
 * <p>数据隔离策略：本次 JVM 生成唯一前缀 {@code QA1-<hex>-}，物料编码、资源编码、
 * 日历名称都带此前缀；每个用例结束后按前缀硬删除，避免软删除残留影响后续用例。
 * 之所以不用事务回滚：CalendarService/ResourceService/MaterialService 未声明事务，
 * 外层回滚无法覆盖其写入；前缀清理对所有路径都有效。</p>
 */
@SpringBootTest(classes = RealDbTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractRealDbIntegrationTest {

    /**
     * 审计操作人，仅用于验证 created_by/updated_by 填充，不伪造系统账号语义。
     */
    protected static final UUID TEST_OPERATOR =
            UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /**
     * 本 JVM 的测试数据前缀。
     */
    protected static final String RUN_PREFIX =
            "QA1-" + Long.toHexString(System.currentTimeMillis()) + "-";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MaterialService materialService;

    @Autowired
    protected BomService bomService;

    @Autowired
    protected ResourceService resourceService;

    @Autowired
    protected CalendarService calendarService;

    @BeforeEach
    void bindTestContext() {
        TenantContext.setTenantId(TenantContext.DEFAULT_TENANT_ID);
        AuditContext.setCurrentUserId(TEST_OPERATOR);
    }

    @AfterEach
    void cleanupTaggedRows() {
        String like = RUN_PREFIX + "%";
        // 先断开 resource/resource_group → calendar 的反向引用，再按依赖顺序硬删
        jdbc.update("UPDATE resource SET calendar_id = NULL WHERE code LIKE ?", like);
        jdbc.update("UPDATE resource_group SET calendar_id = NULL WHERE code LIKE ?", like);
        jdbc.update("DELETE FROM bom_item WHERE child_material_id IN "
                + "(SELECT id FROM material WHERE code LIKE ?)", like);
        jdbc.update("DELETE FROM bom_item WHERE bom_id IN "
                + "(SELECT b.id FROM bom b JOIN material m ON m.id = b.parent_material_id "
                + "WHERE m.code LIKE ?)", like);
        jdbc.update("DELETE FROM bom WHERE parent_material_id IN "
                + "(SELECT id FROM material WHERE code LIKE ?)", like);
        jdbc.update("DELETE FROM calendar WHERE name LIKE ?", like);
        jdbc.update("DELETE FROM resource WHERE code LIKE ?", like);
        jdbc.update("DELETE FROM material WHERE code LIKE ?", like);
        TenantContext.clear();
        AuditContext.clear();
    }

    /**
     * 生成本次测试使用的唯一编码/名称。
     *
     * @param suffix 语义后缀，例如 {@code MAT-P}
     * @return 带运行前缀的唯一值
     */
    protected String tag(String suffix) {
        return RUN_PREFIX + suffix;
    }

    /**
     * 创建物料，类型固定 RAW、单位 PCS，其余字段走服务默认值。
     *
     * @param code 物料编码
     */
    protected void createMaterial(String code) {
        materialService.create(new MaterialCreateCommand(code, "测试物料 " + code, MaterialType.RAW,
                null, "PCS", null, null, null, null, null, null, null));
    }

    /**
     * 按编码取物料主键（物料视图不暴露内部主键）。
     *
     * @param code 物料编码
     * @return 物料 UUID
     */
    protected UUID materialId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM material WHERE code = ? AND deleted = false", UUID.class, code);
    }

    /**
     * 创建 BOM，默认 DRAFT、生效日期 2026-01-01、子件用量 1。
     *
     * @param parentCode 父件编码
     * @param version BOM 业务版本号
     * @param childCodes 子件编码
     * @return 新建 BOM
     */
    protected BomView createBom(String parentCode, String version, String... childCodes) {
        List<BomItemCommand> items = Arrays.stream(childCodes)
                .map(code -> new BomItemCommand(code, BigDecimal.ONE, BigDecimal.ZERO, null, null, false))
                .toList();
        return bomService.create(new BomCreateCommand(parentCode, version, LocalDate.of(2026, 1, 1),
                null, false, null, items));
    }

    /**
     * 激活 BOM。
     *
     * @param bom 待激活 BOM
     * @return 激活后的 BOM
     */
    protected BomView activate(BomView bom) {
        return bomService.activate(bom.id());
    }

    /**
     * 创建资源。
     *
     * @param code 资源编码
     * @param mode 产能模式
     * @param capacityValue 产能值
     * @param batchCapacity 批量，非 BATCH_SECONDS 模式传 null
     * @return 新建资源
     */
    protected ResourceView createResource(String code, CapacityMode mode, BigDecimal capacityValue,
                                          Integer batchCapacity) {
        return resourceService.create(new ResourceCreateCommand(code, "测试资源 " + code,
                ResourceType.PRIMARY, null, mode, capacityValue, batchCapacity, BigDecimal.ONE,
                null, 0, 0, null));
    }

    /**
     * 创建日历。
     *
     * @param name 日历名称
     * @param calendarVersion 业务版本号
     * @param from 生效起始日期
     * @param to 生效结束日期，可空
     * @param type 类型
     * @param resourceId 绑定资源，可空
     * @param shifts 班次
     * @return 新建日历
     */
    protected CalendarResponse createCalendar(String name, int calendarVersion, LocalDate from,
                                              LocalDate to, String type, UUID resourceId,
                                              List<ShiftPayload> shifts) {
        return calendarService.create(new CalendarCreateRequest(name, calendarVersion, type, resourceId,
                null, from, to, shifts, List.of()));
    }

    /**
     * 构造班次。
     *
     * @param start 开始时间 HH:mm
     * @param end 结束时间 HH:mm
     * @param daysOfWeek 生效星期，默认周一
     * @return 班次入参
     */
    protected ShiftPayload shift(String start, String end, Integer... daysOfWeek) {
        if (daysOfWeek == null || daysOfWeek.length == 0) {
            return new ShiftPayload(start, end, List.of(1));
        }
        return new ShiftPayload(start, end, Arrays.asList(daysOfWeek));
    }

    /**
     * 计数查询。
     *
     * @param sql SQL
     * @param args SQL 参数
     * @return 计数结果
     */
    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /**
     * 记录断言证据（会同时写入 target/qa-evidence）。
     *
     * @param message 证据文本
     */
    protected void evidence(String message) {
        Evidence.record(message);
    }
}
