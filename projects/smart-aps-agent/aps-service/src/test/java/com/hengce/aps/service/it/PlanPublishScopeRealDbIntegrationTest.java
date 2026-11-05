package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.schedule.IncrementalSolveRepository;
import com.hengce.aps.service.plan.PlanVersionService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * P1 回归：同一场景内两家客户的增量计划发布，不得互相改写 {@code task.plan_version_id}。
 *
 * <p>现场数据（M3 真实验收）为同一租户单一 BASELINE 场景，A/B 两家客户是同一场景下的
 * 不同订单（不是不同 sandbox）；因此复现用同一 sandbox + 两个订单号，共 19 条任务。</p>
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class PlanPublishScopeRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private PlanVersionService planVersionService;

    @Autowired
    private PlanVersionRepository planVersionRepository;

    @Autowired
    private IncrementalSolveRepository incrementalSolveRepository;

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
    @DisplayName("发布 B 计划后，A 客户 10 条任务的 plan_version_id 必须仍指向 A 计划；B 快照只含 9 条")
    void publishOfCustomerBMustNotRepointCustomerATasks() {
        UUID tenant = newTenant();
        UUID baseline = newSandbox(tenant, "BASELINE", null);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderA = newOrder(tenant, material, "SO-A-CUSTOMER", LocalDate.of(2026, 11, 20));
        UUID orderB = newOrder(tenant, material, "SO-B-CUSTOMER", LocalDate.of(2026, 11, 20));
        for (int index = 1; index <= 10; index++) {
            insertTask(tenant, baseline, orderA, index * 10, resource);
        }
        for (int index = 1; index <= 9; index++) {
            insertTask(tenant, baseline, orderB, index * 10, resource);
        }

        String orderNoA = jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?",
                String.class, orderA);
        String orderNoB = jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?",
                String.class, orderB);

        UUID planA = incrementalSolveRepository.insertDraftVersion(tenant, baseline,
                "PLAN-SCOPE-A", 1, "A 客户增量计划", TEST_OPERATOR, List.of(orderNoA));
        planVersionService.publish(planA, "A 客户发布", null);
        UUID planB = incrementalSolveRepository.insertDraftVersion(tenant, baseline,
                "PLAN-SCOPE-B", 2, "B 客户增量计划", TEST_OPERATOR, List.of(orderNoB));
        planVersionService.publish(planB, "B 客户发布", null);

        long aTasksPointingToA = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND o.order_no = ? AND t.plan_version_id = ?
                """, tenant, orderNoA, planA);
        long aTasksPointingToB = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND o.order_no = ? AND t.plan_version_id = ?
                """, tenant, orderNoA, planB);
        long bTasksPointingToB = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND o.order_no = ? AND t.plan_version_id = ?
                """, tenant, orderNoB, planB);
        int planBTaskCount = jdbc.queryForObject("SELECT task_count FROM plan_version WHERE id = ?",
                Integer.class, planB);

        evidence("P1 before/after: planA=" + planA + ", planB=" + planB
                + ", A tasks -> planA=" + aTasksPointingToA
                + ", A tasks -> planB=" + aTasksPointingToB
                + ", B tasks -> planB=" + bTasksPointingToB
                + ", planB.taskCount=" + planBTaskCount);

        assertThat(aTasksPointingToB)
                .as("发布 B 后 A 客户任务被回填到 B 的 plan_version_id（跨客户回填）")
                .isZero();
        assertThat(aTasksPointingToA).isEqualTo(10);
        assertThat(bTasksPointingToB).isEqualTo(9);
        assertThat(planBTaskCount).isEqualTo(9);
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("P1-TEN-"), "P1 发布范围测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newSandbox(UUID tenant, String type, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, parent_sandbox_id, snapshot)"
                        + " VALUES (?, ?, ?, ?, ?, '{}'::jsonb)",
                id, tenant, tag("P1-SB-") + type, type, parent);
        return id;
    }

    private UUID newMaterial(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("P1-MAT-"), "P1 测试物料");
        return id;
    }

    private UUID newResource(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                        + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                id, tenant, tag("P1-RES-"), "P1 测试资源");
        return id;
    }

    private UUID newOrder(UUID tenant, UUID product, String orderNo, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date)"
                        + " VALUES (?, ?, ?, ?, 10, ?)",
                id, tenant, tag(orderNo), product, dueDate);
        return id;
    }

    private void insertTask(UUID tenant, UUID sandbox, UUID order, int operationIndex, UUID resource) {
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC)
                .plusHours(operationIndex / 10L);
        jdbc.update("""
                INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, resource_id,
                                  start_time, end_time, qty, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 10, 'PLANNED')
                """, tenant, order, sandbox, operationIndex, resource,
                start, start.plusHours(1));
    }
}
