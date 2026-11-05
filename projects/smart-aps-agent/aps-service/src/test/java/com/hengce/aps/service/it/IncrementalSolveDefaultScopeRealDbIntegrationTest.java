package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.IncrementalSolveRepository;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.service.mrp.MrpStatus;
import com.hengce.aps.service.plan.PlanVersionService;
import com.hengce.aps.service.schedule.IncrementalSolveService;
import com.hengce.aps.service.schedule.IncrementalSolveService.IncrementalSolveRequest;
import com.hengce.aps.service.schedule.IncrementalSolveService.IncrementalSolveResult;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

/**
 * 缺陷一（P1）反证式回归：不传 {@code affectedTaskIds} 的“场景全量”增量重排，
 * 也只能把基准求解任务范围内的订单纳入，不得把同一场景内其他客户的任务写进当前版本。
 *
 * <p>真库 + 真 {@link IncrementalSolveService}/{@link PlanVersionService}；只把异步求解
 * 生命周期与 MRP 判定替换为桩，避免用例依赖引擎执行，其余读写全部走生产同款持久层。</p>
 *
 * <p>该用例在修复前会因缺省分支把 A 客户 10 条任务纳入 B 的 DRAFT 范围而变红；
 * 修复后 B 版本下 A 任务必须为 0，且 {@code task_count} 等于 B 自己的 9 条。</p>
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class IncrementalSolveDefaultScopeRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private IncrementalSolveService incrementalSolveService;

    @Autowired
    private PlanVersionService planVersionService;

    @Autowired
    private IncrementalSolveRepository incrementalSolveRepository;

    @MockBean
    private SolveTaskLifecycleService solveTaskLifecycleService;

    @MockBean
    private MrpBlockingChecker mrpBlockingChecker;

    private final List<UUID> tenants = new ArrayList<>();

    @BeforeAll
    static void migrateToLatest() {
        if (!RealDbEnvironment.enabled()) {
            return;
        }
        Flyway.configure()
                .dataSource(RealDbEnvironment.jdbcUrl(), RealDbEnvironment.username(),
                        RealDbEnvironment.password())
                .locations("filesystem:../db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void requireRealDb() {
        Assumptions.assumeTrue(RealDbEnvironment.enabled(), "未启用真库测试：APS_TEST_DB_ENABLED");
    }

    @AfterEach
    void cleanupTenantRows() {
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM task WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM plan_version WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM solve_task WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sandbox WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM resource WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM material WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
        }
        tenants.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("缺省场景全量增量只纳入基准求解范围订单：B 版本下 A 任务为 0，task_count=9")
    void defaultIncrementalMustStayWithinBaseSolveOrderScope() {
        UUID tenant = newTenant();
        UUID sandbox = newSandbox(tenant);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderA1 = newOrder(tenant, material, "SO-A-001", LocalDate.of(2026, 11, 20));
        UUID orderA2 = newOrder(tenant, material, "SO-A-002", LocalDate.of(2026, 11, 20));
        UUID orderB1 = newOrder(tenant, material, "SO-B-001", LocalDate.of(2026, 11, 20));
        UUID orderB2 = newOrder(tenant, material, "SO-B-002", LocalDate.of(2026, 11, 20));
        insertTasks(tenant, sandbox, orderA1, 5, resource, 1);
        insertTasks(tenant, sandbox, orderA2, 5, resource, 6);
        insertTasks(tenant, sandbox, orderB1, 4, resource, 11);
        insertTasks(tenant, sandbox, orderB2, 5, resource, 15);

        String orderNoA1 = orderNo(orderA1);
        String orderNoA2 = orderNo(orderA2);
        String orderNoB1 = orderNo(orderB1);
        String orderNoB2 = orderNo(orderB2);

        long baseSolveTaskId = insertBaseSolveTask(tenant, sandbox, List.of(orderNoB1, orderNoB2));
        stubLifecycle(sandbox);

        IncrementalSolveRequest request = new IncrementalSolveRequest();
        request.setBaseSolveTaskId(baseSolveTaskId);
        request.setSandboxId(sandbox);
        request.setReason("缺省场景全量反证：B 客户增量");
        // 关键：不设置 affectedTaskIds，走 API 默认的“场景全量”语义。

        IncrementalSolveResult result = incrementalSolveService.submit(request, null);

        evidence("缺陷一 default-scope: baseSolveTaskId=" + baseSolveTaskId
                + ", scopeOrderNos=" + result.orderNos()
                + ", affectedOrderCount=" + result.affectedOrderCount());

        planVersionService.publish(result.planId(), "B 客户发布", null);

        long aTasksInPlanB = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND o.order_no IN (?, ?) AND t.plan_version_id = ?
                """, tenant, orderNoA1, orderNoA2, result.planId());
        long bTasksInPlanB = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND o.order_no IN (?, ?) AND t.plan_version_id = ?
                """, tenant, orderNoB1, orderNoB2, result.planId());
        int planTaskCount = jdbc.queryForObject(
                "SELECT task_count FROM plan_version WHERE id = ?", Integer.class, result.planId());

        evidence("缺陷一 publish: A tasks -> planB=" + aTasksInPlanB
                + ", B tasks -> planB=" + bTasksInPlanB
                + ", planB.taskCount=" + planTaskCount);

        assertThat(aTasksInPlanB)
                .as("B 版本下仍出现 A 客户任务（默认路径跨客户回填）")
                .isZero();
        assertThat(bTasksInPlanB).isEqualTo(9);
        assertThat(planTaskCount).isEqualTo(9);
        assertThat(result.orderNos())
                .as("缺省增量把 A 客户订单纳入了 B 的重排范围")
                .containsExactlyInAnyOrder(orderNoB1, orderNoB2);
        assertThat(result.affectedOrderCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("历史基准解无 orderNos：默认路径 42200，文案带 solveTaskId")
    void legacyBaseWithoutOrderNosIsRejectedOnDefaultPath() {
        UUID tenant = newTenant();
        UUID sandbox = newSandbox(tenant);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderA1 = newOrder(tenant, material, "SO-LEG-A1", LocalDate.of(2026, 11, 20));
        UUID orderB1 = newOrder(tenant, material, "SO-LEG-B1", LocalDate.of(2026, 11, 20));
        insertTasks(tenant, sandbox, orderA1, 3, resource, 1);
        insertTasks(tenant, sandbox, orderB1, 3, resource, 4);
        long baseSolveTaskId = insertBaseSolveTask(tenant, sandbox, null);

        IncrementalSolveRequest request = new IncrementalSolveRequest();
        request.setBaseSolveTaskId(baseSolveTaskId);
        request.setSandboxId(sandbox);
        request.setReason("历史基准解：无订单范围");
        // 关键：不设置 affectedTaskIds，走默认路径。

        BizException error = catchThrowableOfType(() -> incrementalSolveService.submit(request, null),
                BizException.class);

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        assertThat(error.getMessage())
                .contains("solveTaskId=" + baseSolveTaskId)
                .contains("全量求解");
        evidence("历史基准解 default: " + error.getMessage());
    }

    @Test
    @DisplayName("历史基准解 orderNos 为空数组：显式 affectedTaskIds 同样 42200")
    void legacyBaseWithEmptyOrderNosIsRejectedOnExplicitPath() {
        UUID tenant = newTenant();
        UUID sandbox = newSandbox(tenant);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderA1 = newOrder(tenant, material, "SO-LEG-EMPTY-A1", LocalDate.of(2026, 11, 20));
        insertTasks(tenant, sandbox, orderA1, 3, resource, 1);
        long baseSolveTaskId = insertBaseSolveTask(tenant, sandbox, List.of());

        IncrementalSolveRequest request = new IncrementalSolveRequest();
        request.setBaseSolveTaskId(baseSolveTaskId);
        request.setSandboxId(sandbox);
        request.setReason("历史基准解：订单范围为空");
        request.setAffectedTaskIds(taskIdsOf(tenant, orderA1));

        BizException error = catchThrowableOfType(() -> incrementalSolveService.submit(request, null),
                BizException.class);

        assertThat(error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        assertThat(error.getMessage()).contains("solveTaskId=" + baseSolveTaskId);
        evidence("历史基准解 explicit: " + error.getMessage());
    }

    @Test
    @DisplayName("带 orderNos 的基准解：显式 affectedTaskIds 正常通过，主流程未被误杀")
    void explicitAffectedTaskIdsStillWorkWithOrderNosScope() {
        UUID tenant = newTenant();
        UUID sandbox = newSandbox(tenant);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderA1 = newOrder(tenant, material, "SO-OK-A1", LocalDate.of(2026, 11, 20));
        UUID orderB1 = newOrder(tenant, material, "SO-OK-B1", LocalDate.of(2026, 11, 20));
        UUID orderB2 = newOrder(tenant, material, "SO-OK-B2", LocalDate.of(2026, 11, 20));
        insertTasks(tenant, sandbox, orderA1, 3, resource, 1);
        insertTasks(tenant, sandbox, orderB1, 4, resource, 4);
        insertTasks(tenant, sandbox, orderB2, 5, resource, 8);

        String orderNoB1 = orderNo(orderB1);
        long baseSolveTaskId = insertBaseSolveTask(tenant, sandbox,
                List.of(orderNoB1, orderNo(orderB2)));
        stubLifecycle(sandbox);

        IncrementalSolveRequest request = new IncrementalSolveRequest();
        request.setBaseSolveTaskId(baseSolveTaskId);
        request.setSandboxId(sandbox);
        request.setReason("带范围基准解：显式指定 B1 任务");
        request.setAffectedTaskIds(taskIdsOf(tenant, orderB1));

        IncrementalSolveResult result = incrementalSolveService.submit(request, null);

        evidence("带 orderNos explicit: baseSolveTaskId=" + baseSolveTaskId
                + ", scopeOrderNos=" + result.orderNos()
                + ", affectedOrderCount=" + result.affectedOrderCount());
        assertThat(result.orderNos()).containsExactly(orderNoB1);
        assertThat(result.affectedOrderCount()).isEqualTo(1);
    }

    private void stubLifecycle(UUID sandbox) {
        when(mrpBlockingChecker.evaluate(any(), anyList(), eq(true)))
                .thenReturn(new MrpBlockingDecision(false, MrpStatus.FULL, List.of(), null));
        SolveTaskDetail detail = new SolveTaskDetail(9001L, sandbox, null, "TIMEFOLD", "PENDING",
                0.0, "QUEUED", null, null, null, 0L, null, null, null, false,
                Map.of(), null, null, null, null, List.of());
        when(solveTaskLifecycleService.submit(any(SolveRequest.class), any(), anyBoolean()))
                .thenReturn(detail);
    }

    private long insertBaseSolveTask(UUID tenant, UUID sandbox, List<String> orderNos) {
        String config = orderNos == null
                ? "{\"engine\":\"TIMEFOLD\",\"parameters\":{}}"
                : orderNos.isEmpty()
                ? "{\"engine\":\"TIMEFOLD\",\"parameters\":{},\"orderNos\":[]}"
                : "{\"engine\":\"TIMEFOLD\",\"parameters\":{},\"orderNos\":[\""
                        + String.join("\",\"", orderNos) + "\"]}";
        Long id = jdbc.queryForObject("""
                INSERT INTO solve_task (tenant_id, sandbox_id, engine, status, config)
                VALUES (?, ?, 'TIMEFOLD', 'SOLVED', ?::jsonb)
                RETURNING id
                """, Long.class, tenant, sandbox, config);
        return id == null ? 0L : id;
    }

    private List<Long> taskIdsOf(UUID tenant, UUID orderId) {
        return jdbc.query("SELECT id FROM task WHERE tenant_id = ? AND order_id = ? ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"), tenant, orderId);
    }

    private String orderNo(UUID order) {
        return jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?", String.class, order);
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("P1-DEF-TEN-"), "P1 缺省范围测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newSandbox(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, 'BASELINE', '{}'::jsonb)",
                id, tenant, tag("P1-DEF-SB-"));
        return id;
    }

    private UUID newMaterial(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("P1-DEF-MAT-"), "P1 缺省范围测试物料");
        return id;
    }

    private UUID newResource(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                        + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                id, tenant, tag("P1-DEF-RES-"), "P1 缺省范围测试资源");
        return id;
    }

    private UUID newOrder(UUID tenant, UUID product, String orderNo, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date)"
                        + " VALUES (?, ?, ?, ?, 10, ?)",
                id, tenant, tag(orderNo), product, dueDate);
        return id;
    }

    private void insertTasks(UUID tenant, UUID sandbox, UUID order, int taskCount, UUID resource,
                             int startIndex) {
        for (int index = 0; index < taskCount; index++) {
            OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC)
                    .plusHours(startIndex + index);
            jdbc.update("""
                    INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, resource_id,
                                      start_time, end_time, qty, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 10, 'PLANNED')
                    """, tenant, order, sandbox, (startIndex + index) * 10, resource,
                    start, start.plusHours(1));
        }
    }
}
