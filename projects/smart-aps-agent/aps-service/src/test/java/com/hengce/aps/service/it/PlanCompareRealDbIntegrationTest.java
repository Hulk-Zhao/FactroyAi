package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.plan.PlanCompareService;
import com.hengce.aps.service.plan.PlanCompareView;
import com.hengce.aps.service.plan.PlanCompareView.PlanCompareItem;
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
 * C35 §25.7.2 择优对比真库集成测试：确定性排序、去重计数、metrics 原样透传、42200 边界。
 *
 * <p>启用条件：{@code APS_TEST_DB_ENABLED=true}；启用后本类不允许任何跳过。</p>
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class PlanCompareRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private PlanCompareService planCompareService;

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
            jdbc.update("DELETE FROM solve_task WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM plan_version WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sandbox WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
        }
        tenants.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("compare：硬分降序→软分降序→planId 升序，同输入重复调用一致，metrics 剔除已移除计数")
    void shouldOrderDeterministicallyAndPassMetricsThrough() {
        UUID tenant = newTenant();
        UUID planA = newPlan(tenant, "PLAN-A", "-1", "10",
                "{\"makespanSeconds\":120,\"fallbackMode\":\"HARD_ONLY\",\"stepCount\":5,"
                        + "\"unimprovedStepCount\":2}");
        UUID planB = newPlan(tenant, "PLAN-B", "-1", "20", "{\"makespanSeconds\":100}");
        UUID planC = newPlan(tenant, "PLAN-C", "0", "5", "{\"makespanSeconds\":90}");

        PlanCompareView first = planCompareService.compare(List.of(planA, planB, planC));
        PlanCompareView second = planCompareService.compare(List.of(planC, planB, planA));

        assertThat(first.items()).extracting(PlanCompareItem::planId)
                .containsExactly(planC, planB, planA);
        assertThat(first.recommendedPlanId()).isEqualTo(planC);
        assertThat(first.tieBreak()).isEqualTo("SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC");
        assertThat(second.items()).isEqualTo(first.items());
        assertThat(second.recommendedPlanId()).isEqualTo(first.recommendedPlanId());

        PlanCompareItem itemA = first.items().get(2);
        assertThat(itemA.metrics()).containsEntry("makespanSeconds", 120);
        assertThat(itemA.metrics()).containsEntry("fallbackMode", "HARD_ONLY");
        assertThat(itemA.metrics()).doesNotContainKeys("stepCount", "unimprovedStepCount");
        evidence("C35 compare: order=" + planC + "," + planB + "," + planA
                + "; recommended=" + first.recommendedPlanId());
    }

    @Test
    @DisplayName("compare：去重后 4 个 → 40000；无快照 → 42200；不存在 → 42200")
    void shouldRejectInvalidPlanIdSetsAndMissingSnapshots() {
        UUID tenant = newTenant();
        UUID planA = newPlan(tenant, "PLAN-A", "0", "10", "{\"makespanSeconds\":1}");
        UUID planB = newPlan(tenant, "PLAN-B", "0", "10", "{\"makespanSeconds\":2}");
        UUID planC = newPlan(tenant, "PLAN-C", "0", "10", "{\"makespanSeconds\":3}");
        UUID planD = newPlan(tenant, "PLAN-D", "0", "10", "{\"makespanSeconds\":4}");

        assertThatThrownBy(() -> planCompareService.compare(List.of(planA, planB, planC, planD)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("去重后必须为 2–3 个")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);

        UUID noSnapshot = newPlanWithSnapshot(tenant, "PLAN-EMPTY", "{}");
        assertThatThrownBy(() -> planCompareService.compare(List.of(planA, noSnapshot)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无快照")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);

        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> planCompareService.compare(List.of(planA, missing)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("计划不存在")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);

        evidence("C35 compare 42200 边界: 4 unique / no snapshot / missing plan 均被拒绝");
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("W2-CTEN-"), "W2 择优测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newPlan(UUID tenant, String planNo, String scoreHard, String scoreSoft,
                         String metricsJson) {
        UUID sandbox = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, 'WHAT_IF', '{}'::jsonb)",
                sandbox, tenant, tag("W2-CSB-" + planNo + "-"));
        jdbc.update("INSERT INTO solve_task (tenant_id, sandbox_id, engine, status, score_hard,"
                        + " score_soft, started_at, finished_at, metrics)"
                        + " VALUES (?, ?, 'TIMEFOLD', 'SOLVED', ?::bigint, ?::bigint,"
                        + " now() - interval '3 minutes', now() - interval '2 minutes', ?::jsonb)",
                tenant, sandbox, scoreHard, scoreSoft, metricsJson);
        return newPlanWithSandbox(tenant, sandbox, planNo, "{\"tasks\":[{\"taskId\":\"t1\"}]}");
    }

    private UUID newPlanWithSnapshot(UUID tenant, String planNo, String snapshotJson) {
        UUID sandbox = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, 'WHAT_IF', '{}'::jsonb)",
                sandbox, tenant, tag("W2-CSB-" + planNo + "-"));
        return newPlanWithSandbox(tenant, sandbox, planNo, snapshotJson);
    }

    private UUID newPlanWithSandbox(UUID tenant, UUID sandbox, String planNo, String snapshotJson) {
        UUID planId = UUID.randomUUID();
        jdbc.update("INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,"
                        + " snapshot, task_count, published_at, version)"
                        + " VALUES (?, ?, ?, ?, '12', 'PUBLISHED', ?::jsonb, 1,"
                        + " now() - interval '1 minute', 1)",
                planId, tenant, sandbox, tag(planNo), snapshotJson);
        return planId;
    }
}
