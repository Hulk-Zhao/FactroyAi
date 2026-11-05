package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.IncrementalSolveRepository;
import com.hengce.aps.service.plan.PlanActionView;
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
 * 缺陷二回归：求解任务处于 {@code PENDING/RUNNING} 时，发布必须被接口自身拒绝（42200），
 * 求解进入终态后才允许发布并回填 {@code task.plan_version_id}。
 *
 * <p>现场链路：增量求解异步创建 {@code solve_task} 并立即返回；若此时发布，快照会固化尚未重建的
 * 旧任务，求解完成重建后 {@code plan_version_id} 全部落空。防护必须落在发布入口，不能依赖前端置灰。</p>
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class PlanPublishActiveSolveGuardRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private PlanVersionService planVersionService;

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
    @DisplayName("RUNNING 期间发布被 42200 拒绝并指出 solveTaskId；终态后发布成功且 plan_version_id 有值")
    void publishWhileSolveRunningMustBeRejectedThenSucceedAfterSolved() {
        UUID tenant = newTenant();
        UUID sandbox = newSandbox(tenant);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderB = newOrder(tenant, material, "SO-ACTIVE-B", LocalDate.of(2026, 11, 20));
        for (int index = 1; index <= 4; index++) {
            insertTask(tenant, sandbox, orderB, index * 10, resource);
        }
        String orderNoB = jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?",
                String.class, orderB);

        UUID planId = incrementalSolveRepository.insertDraftVersion(tenant, sandbox,
                "PLAN-ACTIVE-GUARD", 1, "RUNNING 防护测试", TEST_OPERATOR, List.of(orderNoB));
        long runningSolveTaskId = insertSolveTask(tenant, sandbox, "RUNNING");

        evidence("缺陷二 running: planId=" + planId + ", solveTaskId=" + runningSolveTaskId);

        assertThatThrownBy(() -> planVersionService.publish(planId, "RUNNING 期间发布", null))
                .as("求解未终态时必须由发布接口拒绝")
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException biz = (BizException) exception;
                    assertThat(biz.getErrorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
                    assertThat(biz.getMessage())
                            .contains("solveTaskId=" + runningSolveTaskId)
                            .contains("RUNNING");
                });

        String statusAfterReject = jdbc.queryForObject(
                "SELECT status FROM plan_version WHERE id = ?", String.class, planId);
        long boundWhileRunning = count(
                "SELECT COUNT(1) FROM task WHERE tenant_id = ? AND plan_version_id = ?",
                tenant, planId);

        evidence("缺陷二 rejected: planStatus=" + statusAfterReject
                + ", boundTasks=" + boundWhileRunning);

        assertThat(statusAfterReject).isEqualTo("DRAFT");
        assertThat(boundWhileRunning).isZero();

        jdbc.update("UPDATE solve_task SET status = 'SOLVED', finished_at = now() WHERE id = ?",
                runningSolveTaskId);

        PlanActionView published = planVersionService.publish(planId, "求解完成后发布", null);

        long boundAfterSolved = count(
                "SELECT COUNT(1) FROM task WHERE tenant_id = ? AND plan_version_id = ?",
                tenant, planId);
        int planTaskCount = jdbc.queryForObject(
                "SELECT task_count FROM plan_version WHERE id = ?", Integer.class, planId);

        evidence("缺陷二 solved: planStatus=" + published.status()
                + ", planTaskCount=" + planTaskCount + ", boundTasks=" + boundAfterSolved);

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(boundAfterSolved).isEqualTo(4);
        assertThat(planTaskCount).isEqualTo(4);
    }

    private long insertSolveTask(UUID tenant, UUID sandbox, String status) {
        Long id = jdbc.queryForObject("""
                INSERT INTO solve_task (tenant_id, sandbox_id, engine, status, started_at, config)
                VALUES (?, ?, 'TIMEFOLD', ?, now(), '{"parameters":{}}'::jsonb)
                RETURNING id
                """, Long.class, tenant, sandbox, status);
        return id == null ? 0L : id;
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("P1-GUARD-TEN-"), "P1 发布防护测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newSandbox(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, 'BASELINE', '{}'::jsonb)",
                id, tenant, tag("P1-GUARD-SB-"));
        return id;
    }

    private UUID newMaterial(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("P1-GUARD-MAT-"), "P1 发布防护测试物料");
        return id;
    }

    private UUID newResource(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                        + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                id, tenant, tag("P1-GUARD-RES-"), "P1 发布防护测试资源");
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
