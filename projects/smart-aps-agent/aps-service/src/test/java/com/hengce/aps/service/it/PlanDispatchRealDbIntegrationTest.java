package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository;
import com.hengce.aps.service.notification.DispatchResultView;
import com.hengce.aps.service.notification.DispatchSummaryView;
import com.hengce.aps.service.notification.NotificationOutboxService;
import com.hengce.aps.service.notification.PlanDispatchService;
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
 * C33 下发真库集成测试：PUBLISHED 才可下发、同 key 只一条、outbox 状态流转与重试不重复。
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class PlanDispatchRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private PlanDispatchService planDispatchService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

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
    @DisplayName("dispatch：同 key 只入队一次，dispatches 聚合为 QUEUED，NOOP 处理后转 SENT")
    void shouldDispatchIdempotentlyAndFlowStatus() {
        UUID tenant = newTenant();
        UUID planId = newPlan(tenant, "PUBLISHED");
        List<PlanDispatchService.DispatchTargetCommand> targets = List.of(
                new PlanDispatchService.DispatchTargetCommand("EMAIL", "qa@example.com"),
                new PlanDispatchService.DispatchTargetCommand("SMS", "13800000000"));

        DispatchResultView first = planDispatchService.dispatch(planId, "w5-dispatch-key-01", targets, "ready");
        DispatchResultView second = planDispatchService.dispatch(planId, "w5-dispatch-key-01", targets, "ready");

        assertThat(first.dispatchId()).isEqualTo(second.dispatchId());
        assertThat(first.status()).isEqualTo("QUEUED");
        assertThat(first.targetCount()).isEqualTo(2);
        assertThat(count("SELECT COUNT(1) FROM notification_outbox WHERE tenant_id = ?"
                + " AND dispatch_id = ?", tenant, first.dispatchId())).isEqualTo(2);

        List<DispatchSummaryView> summaries = planDispatchService
                .listDispatches(planId, new com.hengce.aps.common.api.PageQuery())
                .items();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo("QUEUED");
        assertThat(summaries.get(0).targetCount()).isEqualTo(2);
        evidence("C33 dispatch 幂等: dispatchId=" + first.dispatchId() + ", outboxRows=2");

        NotificationOutboxService outboxService = new NotificationOutboxService(outboxRepository, row -> {
        });
        assertThat(outboxService.processDue(10)).isEqualTo(2);
        assertThat(count("SELECT COUNT(1) FROM notification_outbox WHERE tenant_id = ?"
                + " AND status = 'SENT'", tenant)).isEqualTo(2);
        assertThat(outboxService.processDue(10)).isZero();

        List<DispatchSummaryView> afterSend = planDispatchService
                .listDispatches(planId, new com.hengce.aps.common.api.PageQuery())
                .items();
        assertThat(afterSend.get(0).status()).isEqualTo("SENT");
        assertThat(afterSend.get(0).sentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("通道失败：退避 30s、第二次仍失败转 FAILED，行数不增")
    void shouldRetryWithoutDuplicatingRows() {
        UUID tenant = newTenant();
        UUID planId = newPlan(tenant, "PUBLISHED");
        DispatchResultView dispatch = planDispatchService.dispatch(planId, "w5-dispatch-key-02",
                List.of(new PlanDispatchService.DispatchTargetCommand("FEISHU", "ou_12345")), null);

        NotificationOutboxService failing = new NotificationOutboxService(outboxRepository, row -> {
            throw new IllegalStateException("feishu down");
        });
        assertThat(failing.processDue(10)).isEqualTo(1);
        assertThat(count("SELECT COUNT(1) FROM notification_outbox WHERE tenant_id = ?"
                + " AND dispatch_id = ?", tenant, dispatch.dispatchId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_outbox WHERE dispatch_id = ?",
                Integer.class, dispatch.dispatchId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE dispatch_id = ?",
                String.class, dispatch.dispatchId())).isEqualTo("QUEUED");
        OffsetDateTime nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM notification_outbox WHERE dispatch_id = ?",
                OffsetDateTime.class, dispatch.dispatchId());
        assertThat(nextAttempt).isAfter(OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(NotificationOutboxService.BACKOFF_SECONDS - 2));
        assertThat(failing.processDue(10)).isZero();

        jdbc.update("UPDATE notification_outbox SET next_attempt_at = now() WHERE dispatch_id = ?",
                dispatch.dispatchId());
        assertThat(failing.processDue(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_outbox WHERE dispatch_id = ?",
                Integer.class, dispatch.dispatchId())).isEqualTo(NotificationOutboxService.MAX_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE dispatch_id = ?",
                String.class, dispatch.dispatchId())).isEqualTo("FAILED");
        assertThat(count("SELECT COUNT(1) FROM notification_outbox WHERE tenant_id = ?"
                + " AND dispatch_id = ?", tenant, dispatch.dispatchId())).isEqualTo(1);
        evidence("C33 重试: attempts=2, status=FAILED, rows=1（未重复通知）");
    }

    @Test
    @DisplayName("非 PUBLISHED 计划拒绝下发且不产生 outbox 行")
    void shouldRejectNonPublishedPlan() {
        UUID tenant = newTenant();
        UUID planId = newPlan(tenant, "DRAFT");

        assertThatThrownBy(() -> planDispatchService.dispatch(planId, "w5-dispatch-key-03",
                List.of(new PlanDispatchService.DispatchTargetCommand("EMAIL", "qa@example.com")), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅 PUBLISHED")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);

        assertThat(count("SELECT COUNT(1) FROM notification_outbox WHERE tenant_id = ?", tenant)).isZero();
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("W5-DTEN-"), "W5 下发测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newPlan(UUID tenant, String status) {
        UUID sandbox = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, 'BASELINE', '{}'::jsonb)",
                sandbox, tenant, tag("W5-DSB-"));
        UUID planId = UUID.randomUUID();
        jdbc.update("INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,"
                        + " published_at, task_count) VALUES (?, ?, ?, ?, '1', ?, "
                        + "CASE WHEN ? = 'PUBLISHED' THEN now() ELSE NULL END, 0)",
                planId, tenant, sandbox, tag("PLAN-"), status, status);
        return planId;
    }
}
