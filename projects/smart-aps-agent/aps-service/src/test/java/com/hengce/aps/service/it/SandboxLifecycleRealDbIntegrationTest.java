package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.sandbox.SandboxApplyResultView;
import com.hengce.aps.service.sandbox.SandboxCompareView;
import com.hengce.aps.service.sandbox.SandboxService;
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
 * C32 场景生命周期真库集成测试：apply 单事务/失败回滚/唯一 BASELINE、重复应用拒绝、compare 三种计数。
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class SandboxLifecycleRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private SandboxService sandboxService;

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
            jdbc.update("DELETE FROM notification_outbox WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM task_adjustment WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM task WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM solve_task WHERE tenant_id = ?", tenant);
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
    @DisplayName("apply：发布新版本、旧 BASELINE 归档、目标提升、重复应用拒绝、唯一 BASELINE 生效")
    void shouldApplyWhatIfAsNewBaseline() {
        UUID tenant = newTenant();
        UUID baseline = newSandbox(tenant, "BASELINE", null);
        UUID whatIf = newSandbox(tenant, "WHAT_IF", baseline);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID order = newOrder(tenant, material, "SO-APPLY", LocalDate.of(2026, 11, 20));
        insertTask(tenant, whatIf, order, 10, resource, utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0));
        insertTask(tenant, whatIf, order, 20, resource, utc(2026, 11, 5, 9, 0), utc(2026, 11, 5, 10, 0));

        SandboxApplyResultView result = sandboxService.apply(whatIf, "W5 apply evidence");

        assertThat(result.plan().status()).isEqualTo("PUBLISHED");
        assertThat(result.plan().taskCount()).isEqualTo(2);
        assertThat(result.sandbox().type()).isEqualTo("BASELINE");
        assertThat(result.sandbox().version()).isEqualTo(2);

        assertThat(count("SELECT COUNT(1) FROM plan_version WHERE tenant_id = ? AND sandbox_id = ?"
                + " AND status = 'PUBLISHED'", tenant, whatIf)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM sandbox WHERE id = ?", String.class, baseline))
                .isEqualTo("ARCHIVED");
        assertThat(count("SELECT COUNT(1) FROM sandbox WHERE tenant_id = ? AND type = 'BASELINE'"
                + " AND deleted = false", tenant)).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM task WHERE tenant_id = ? AND sandbox_id = ?"
                + " AND plan_version_id = ?", tenant, whatIf, result.plan().planId())).isEqualTo(2);
        evidence("C32 apply: planId=" + result.plan().planId() + ", version=" + result.plan().version()
                + ", taskCount=" + result.plan().taskCount() + ", baselineCount=1");

        assertThatThrownBy(() -> sandboxService.apply(whatIf, "second"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已应用");
        assertThat(count("SELECT COUNT(1) FROM plan_version WHERE tenant_id = ?", tenant)).isEqualTo(1);

        assertThatThrownBy(() -> newSandbox(tenant, "BASELINE", null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("apply 失败回滚：缺旧 BASELINE 时抛错，不留半截 plan_version")
    void shouldRollbackEntireApplyWhenLaterStepFails() {
        UUID tenant = newTenant();
        UUID whatIf = newSandbox(tenant, "WHAT_IF", null);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID order = newOrder(tenant, material, "SO-ROLLBACK", LocalDate.of(2026, 11, 20));
        long taskId = insertTask(tenant, whatIf, order, 10, resource,
                utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0));

        assertThatThrownBy(() -> sandboxService.apply(whatIf, "fail after publish"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少 BASELINE")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        assertThat(count("SELECT COUNT(1) FROM plan_version WHERE tenant_id = ?", tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT type FROM sandbox WHERE id = ?", String.class, whatIf))
                .isEqualTo("WHAT_IF");
        assertThat(jdbc.queryForObject("SELECT version FROM sandbox WHERE id = ?", Integer.class, whatIf))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plan_version_id FROM task WHERE id = ?", UUID.class, taskId))
                .isNull();
        evidence("C32 apply 失败回滚: plan_version=0, sandboxType=WHAT_IF, task.plan_version_id=null");
    }

    @Test
    @DisplayName("compare：added/removed/moved 三种计数且方向为 id - targetId")
    void shouldComputeCompareCounts() {
        UUID tenant = newTenant();
        UUID baseline = newSandbox(tenant, "BASELINE", null);
        UUID whatIf = newSandbox(tenant, "WHAT_IF", baseline);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID orderA = newOrder(tenant, material, "SO-COMPARE-A", LocalDate.of(2026, 11, 20));
        UUID orderB = newOrder(tenant, material, "SO-COMPARE-B", LocalDate.of(2026, 11, 20));
        UUID orderC = newOrder(tenant, material, "SO-COMPARE-C", LocalDate.of(2026, 11, 20));

        insertTask(tenant, baseline, orderA, 10, resource, utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0));
        insertTask(tenant, baseline, orderB, 10, resource, utc(2026, 11, 5, 9, 0), utc(2026, 11, 5, 10, 0));
        insertTask(tenant, whatIf, orderA, 10, resource, utc(2026, 11, 5, 10, 0), utc(2026, 11, 5, 11, 0));
        insertTask(tenant, whatIf, orderC, 10, resource, utc(2026, 11, 5, 11, 0), utc(2026, 11, 5, 12, 0));

        SandboxCompareView view = sandboxService.compare(whatIf, baseline);

        assertThat(view.sandboxId()).isEqualTo(whatIf);
        assertThat(view.targetId()).isEqualTo(baseline);
        assertThat(view.addedTaskCount()).isEqualTo(1);
        assertThat(view.removedTaskCount()).isEqualTo(1);
        assertThat(view.movedTaskCount()).isEqualTo(1);
        assertThat(view.changedTasks()).extracting(SandboxCompareView.ChangedTaskView::changeType)
                .containsExactlyInAnyOrder("ADDED", "MOVED", "REMOVED");
        assertThat(view.truncated()).isFalse();
        evidence("C32 compare: added=1 removed=1 moved=1 direction=" + whatIf + " - " + baseline);
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("W5-TEN-"), "W5 场景测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newSandbox(UUID tenant, String type, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, parent_sandbox_id, snapshot)"
                        + " VALUES (?, ?, ?, ?, ?, '{}'::jsonb)",
                id, tenant, tag("W5-SB-") + type, type, parent);
        return id;
    }

    private UUID newMaterial(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("W5-MAT-"), "W5 测试物料");
        return id;
    }

    private UUID newResource(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                id, tenant, tag("W5-RES-"), "W5 测试资源");
        return id;
    }

    private UUID newOrder(UUID tenant, UUID product, String orderNo, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date)"
                + " VALUES (?, ?, ?, ?, 10, ?)",
                id, tenant, tag(orderNo), product, dueDate);
        return id;
    }

    private long insertTask(UUID tenant, UUID sandbox, UUID order, int operationIndex, UUID resource,
                            OffsetDateTime start, OffsetDateTime end) {
        Long id = jdbc.queryForObject("""
                INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, resource_id,
                                  start_time, end_time, qty, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 10, 'PLANNED')
                RETURNING id
                """, Long.class, tenant, order, sandbox, operationIndex, resource, start, end);
        return id == null ? 0L : id;
    }

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
