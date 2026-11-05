package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 装载口径回归（真库）：请求订单之外的场景既有任务必须按 horizon 相交范围读取，
 * 供装配器作为 pinned 既有占用装入题面。
 *
 * <p>验证三件事：①排除本次请求订单；②只取与 horizon 相交的既有任务订单（远未来任务
 * 不与新排任务冲突，装进题面反而会因可用窗口不足产生假违约）；③场景隔离。</p>
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
class ScheduleRepositoryExistingTaskScopeRealDbTest extends AbstractRealDbIntegrationTest {

    private static final OffsetDateTime HORIZON_START =
            OffsetDateTime.parse("2026-11-04T22:00:00Z");
    private static final OffsetDateTime HORIZON_END =
            OffsetDateTime.parse("2026-11-12T02:00:00Z");

    @Autowired
    private ScheduleRepository scheduleRepository;

    private UUID tenant;
    private UUID sandbox;
    private UUID otherSandbox;

    @BeforeEach
    void requireRealDb() {
        Assumptions.assumeTrue(RealDbEnvironment.enabled(), "未启用真库测试：APS_TEST_DB_ENABLED");
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("DEF-SCOPE-TEN-"), "装载范围回归租户");
        TenantContext.setTenantId(tenant);
        sandbox = newSandbox("SB-A", "BASELINE");
        otherSandbox = newSandbox("SB-B", "WHAT_IF");
    }

    @AfterEach
    void cleanupOrderRows() {
        if (tenant == null) {
            return;
        }
        jdbc.update("DELETE FROM task WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM sandbox WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM material WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
        TenantContext.clear();
    }

    @Test
    @DisplayName("排除请求订单 + 只取 horizon 内既有任务 + 场景隔离")
    void existingTasksMustBeScopedAndHorizonFiltered() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String requestedNo = "SO-REQ-" + suffix;
        String inHorizonNo = "SO-OTHER-IN-" + suffix;
        UUID material = newMaterial();
        UUID requested = newOrder(material, requestedNo, HORIZON_START.plusDays(1));
        UUID inHorizon = newOrder(material, inHorizonNo, HORIZON_START.plusDays(2));
        UUID outOfHorizon = newOrder(material, "SO-OTHER-FAR-" + suffix, HORIZON_START.plusDays(30));
        UUID otherSandboxOrder = newOrder(material, "SO-OTHER-SB-" + suffix, HORIZON_START.plusDays(3));

        insertTask(requested, sandbox, HORIZON_START.plusHours(8));
        insertTask(inHorizon, sandbox, HORIZON_START.plusHours(30));
        insertTask(outOfHorizon, sandbox, HORIZON_START.plusDays(30));
        insertTask(otherSandboxOrder, otherSandbox, HORIZON_START.plusHours(20));

        List<OrderSourceRow> withExclude = scheduleRepository.findOrdersWithExistingTasks(
                tenant, sandbox, List.of(requested), HORIZON_START, HORIZON_END);
        List<OrderSourceRow> withoutExclude = scheduleRepository.findOrdersWithExistingTasks(
                tenant, sandbox, List.of(), HORIZON_START, HORIZON_END);

        evidence("装载范围真库: withExclude=" + orderNos(withExclude)
                + ", withoutExclude=" + orderNos(withoutExclude));

        assertThat(orderNos(withExclude))
                .as("请求订单必须排除；horizon 外订单与另一场景订单不得进入题面")
                .containsExactly(inHorizonNo);
        assertThat(orderNos(withoutExclude))
                .containsExactlyInAnyOrder(requestedNo, inHorizonNo);
        assertThat(withExclude.get(0).qty()).isEqualByComparingTo(BigDecimal.TEN);
    }

    private List<String> orderNos(List<OrderSourceRow> rows) {
        return rows.stream().map(OrderSourceRow::orderNo).toList();
    }

    private UUID newSandbox(String suffix, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, ?, '{}'::jsonb)",
                id, tenant, tag("DEF-SCOPE-" + suffix + "-"), type);
        return id;
    }

    private UUID newMaterial() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("DEF-SCOPE-MAT-"), "装载范围回归物料");
        return id;
    }

    private UUID newOrder(UUID material, String orderNo, OffsetDateTime dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date)"
                        + " VALUES (?, ?, ?, ?, 10, ?)",
                id, tenant, orderNo, material, dueDate.toLocalDate());
        return id;
    }

    private void insertTask(UUID order, UUID taskSandbox, OffsetDateTime start) {
        jdbc.update("""
                INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index,
                                  start_time, end_time, qty, status)
                VALUES (?, ?, ?, 1, ?, ?, 10, 'PLANNED')
                """, tenant, order, taskSandbox, start, start.plusHours(1));
    }
}
