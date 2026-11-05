package com.hengce.aps.service.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.service.agent.AgentEventRequest;
import com.hengce.aps.service.agent.AgentEventService;
import com.hengce.aps.service.agent.AgentEventView;
import com.hengce.aps.service.agent.AgentExplainService;
import com.hengce.aps.service.agent.AgentExplainView;
import com.hengce.aps.service.agent.AgentWhatIfRequest;
import com.hengce.aps.service.agent.AgentWhatIfService;
import com.hengce.aps.service.agent.AgentWhatIfView;
import com.hengce.aps.service.masterdata.material.MaterialCreateCommand;
import com.hengce.aps.service.masterdata.order.OrderCreateCommand;
import com.hengce.aps.service.masterdata.order.OrderPriority;
import com.hengce.aps.service.masterdata.order.OrderService;
import com.hengce.aps.service.masterdata.order.OrderSource;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import com.hengce.aps.service.process.routing.RoutingCreateCommand;
import com.hengce.aps.service.process.routing.RoutingEdgeCommand;
import com.hengce.aps.service.process.routing.RoutingNodeCommand;
import com.hengce.aps.service.process.routing.RoutingService;
import com.hengce.aps.service.sandbox.SandboxService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Agent 确定性能力真库集成测试（C45）：
 * explain 证据映射、events 幂等审计、whatif 沙箱对比与参数边界。
 *
 * <p>不启动 Web 层，不调用任何模型服务；whatif 的求解使用测试内联的确定性
 * {@code SolverAdapter} 替身（与 {@link CoreFlowE2ETest} 同一实现），
 * 保证断言的是既有求解/沙箱链路而不是平行逻辑。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@TestPropertySource(properties = {"aps.solve.async-enabled=false", "aps.mrp.blocking-enabled=false"})
@SpringBootTest(classes = RealDbTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CoreFlowE2ETest.DeterministicSolverConfiguration.class)
class AgentDeterministicRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AgentEventService agentEventService;

    @Autowired
    private AgentExplainService agentExplainService;

    @Autowired
    private AgentWhatIfService agentWhatIfService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private RoutingService routingService;

    @Autowired
    private SandboxService sandboxService;

    private final List<UUID> tenants = new ArrayList<>();

    @AfterEach
    void cleanupAgentRows() {
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM notification_outbox WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM task_adjustment WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM constraint_justification WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM task WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM solve_task WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM plan_version WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM routing_edge WHERE routing_id IN "
                    + "(SELECT id FROM routing WHERE tenant_id = ?)", tenant);
            jdbc.update("DELETE FROM routing_node WHERE routing_id IN "
                    + "(SELECT id FROM routing WHERE tenant_id = ?)", tenant);
            jdbc.update("DELETE FROM routing WHERE tenant_id = ?", tenant);
            jdbc.update("UPDATE resource SET calendar_id = NULL WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM calendar WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sandbox WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM resource WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM material WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM agent_checkpoint WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM agent_run WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM agent_prompt_version WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenant);
        }
        tenants.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("events：同一事件重复上报不新增 audit_log 行，且拒绝客户端伪造 userId")
    void shouldReportEventIdempotentlyOnAuditLog() throws Exception {
        UUID tenant = newTenant();
        String key = "evt:" + UUID.randomUUID();
        AgentEventRequest request = new AgentEventRequest("ORDER_STATUS_CHANGED", tag("SO-EVT"), null,
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                OBJECT_MAPPER.readTree("{\"from\":\"CONFIRMED\",\"to\":\"RELEASED\"}"), null, null);

        AgentEventView first = agentEventService.report(request, key);
        AgentEventView second = agentEventService.report(request, key);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.auditLogId()).isEqualTo(first.auditLogId());
        assertThat(second.tenantId()).isEqualTo(tenant);
        assertThat(second.userId()).isEqualTo(TEST_OPERATOR);
        assertThat(second.payload()).containsEntry("to", "RELEASED");
        assertThat(count("SELECT COUNT(1) FROM audit_log WHERE tenant_id = ? AND action = ?"
                + " AND resource_id = ?", tenant, AgentEventService.AUDIT_ACTION, key)).isEqualTo(1);

        assertThatThrownBy(() -> agentEventService.report(new AgentEventRequest(
                "ORDER_STATUS_CHANGED", tag("SO-EVT"), null, OffsetDateTime.now(ZoneOffset.UTC).toString(),
                null, UUID.randomUUID().toString(), null), "evt:" + UUID.randomUUID()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(count("SELECT COUNT(1) FROM audit_log WHERE tenant_id = ?", tenant)).isEqualTo(1);
        evidence("C45 events: auditLogId=" + first.auditLogId() + ", duplicate=" + second.duplicate()
                + ", userId=" + second.userId() + ", rows=1");
    }

    @Test
    @DisplayName("explain：按 implicated_facts 唯一定位 solve_task，输出扣分证据与计划版本")
    void shouldExplainTaskWithJustificationEvidence() {
        UUID tenant = newTenant();
        UUID sandbox = newSandbox(tenant, "WHAT_IF", null);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID order = newOrder(tenant, material, tag("SO-EXP"), LocalDate.of(2026, 11, 20));
        long taskId = insertTask(tenant, sandbox, order, 10, resource,
                utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0));
        long solveTaskId = insertSolveTask(tenant, sandbox, "SOLVED", 0L, 12L);
        UUID planId = insertPlanVersion(tenant, sandbox);
        jdbc.update("UPDATE task SET plan_version_id = ? WHERE id = ?", planId, taskId);
        UUID justificationId = insertJustification(tenant, solveTaskId, taskId,
                "C-DUE-DATE", "交期优先", "SOFT", 30, new BigDecimal("12"));

        AgentExplainView view = agentExplainService.explain(taskId);

        assertThat(view.available()).isTrue();
        assertThat(view.source()).isEqualTo("EVIDENCE");
        assertThat(view.sourceSolveTaskId()).isEqualTo(solveTaskId);
        assertThat(view.justificationIds()).containsExactly(justificationId.toString());
        assertThat(view.constraintImpacts()).hasSize(1);
        assertThat(view.constraintImpacts().get(0).scoreImpact()).isEqualTo("SOFT");
        assertThat(view.constraintImpacts().get(0).deduction()).isEqualByComparingTo("12");
        assertThat(view.constraintImpacts().get(0).affectedTaskIds()).contains(taskId);
        assertThat(view.causes().get(0).evidence())
                .containsEntry("sourceSolveTaskId", solveTaskId)
                .containsEntry("justificationId", justificationId.toString());
        assertThat(view.planVersions()).hasSize(1);
        assertThat(view.planVersions().get(0).planId()).isEqualTo(planId);
        assertThat(view.llm().configured()).isFalse();
        assertThat(view.narrative()).isEqualTo(
                "AI 解读未启用（管理员未配置模型服务）。下方为求解结果中的原始归因，可正常使用。");
        evidence("C45 explain: taskId=" + taskId + ", solveTaskId=" + solveTaskId
                + ", justification=" + justificationId + ", planVersions=1");
    }

    @Test
    @DisplayName("explain：无归因与多 solve_task 歧义时 available=false，不猜测")
    void shouldNotGuessWhenJustificationMissingOrAmbiguous() {
        UUID tenant = newTenant();
        UUID sandboxA = newSandbox(tenant, "WHAT_IF", null);
        UUID material = newMaterial(tenant);
        UUID resource = newResource(tenant);
        UUID order = newOrder(tenant, material, tag("SO-AMB"), LocalDate.of(2026, 11, 20));
        long taskId = insertTask(tenant, sandboxA, order, 10, resource,
                utc(2026, 11, 5, 8, 0), utc(2026, 11, 5, 9, 0));

        AgentExplainView noData = agentExplainService.explain(taskId);
        assertThat(noData.available()).isFalse();
        assertThat(noData.noDataReason()).isEqualTo("NO_JUSTIFICATION_RECORD");
        assertThat(noData.sourceSolveTaskId()).isNull();
        assertThat(noData.narrative()).isEqualTo(
                "该求解任务没有归因记录。归因由求解结果写入，早期求解可能没有记录。");

        long solveTaskA = insertSolveTask(tenant, sandboxA, "SOLVED", 0L, 3L);
        UUID sandboxB = newSandbox(tenant, "WHAT_IF", null);
        long solveTaskB = insertSolveTask(tenant, sandboxB, "SOLVED", 0L, 5L);
        insertJustification(tenant, solveTaskA, taskId, "C-A", "约束 A", "HARD", null, BigDecimal.ZERO);
        insertJustification(tenant, solveTaskB, taskId, "C-B", "约束 B", "SOFT", 10, BigDecimal.ONE);

        AgentExplainView ambiguous = agentExplainService.explain(taskId);
        assertThat(ambiguous.available()).isFalse();
        assertThat(ambiguous.noDataReason()).isEqualTo("AMBIGUOUS_SOLVE_TASK");
        assertThat(ambiguous.sourceSolveTaskCandidates()).containsExactly(solveTaskA, solveTaskB);
        assertThat(ambiguous.sourceSolveTaskId()).isNull();
        assertThat(ambiguous.constraintImpacts()).isEmpty();
        evidence("C45 explain noData: reason=" + noData.noDataReason()
                + ", ambiguous candidates=" + ambiguous.sourceSolveTaskCandidates());
    }

    @Test
    @DisplayName("whatif：WHAT_IF 场景真实求解后复用沙箱 compare 返回方案对比与瓶颈变化")
    void shouldRunWhatIfThroughSandboxComparison() {
        UUID tenant = newTenant();
        UUID baseline = newSandbox(tenant, "BASELINE", null);
        UUID whatIf = newSandbox(tenant, "WHAT_IF", baseline);
        String productCode = tag("FG-WI");
        materialService.create(new MaterialCreateCommand(productCode, "C45 成品", MaterialType.FINISHED,
                null, "PCS", null, null, null, null, null, null, null));
        ResourceView resource = createResource(tag("RES-WI"), CapacityMode.UNIT_PER_HOUR,
                new BigDecimal("10"), null);
        createCalendar(tag("CAL-WI"), 1, LocalDate.of(2026, 1, 1), null, "FACTORY", null,
                List.of(shift("00:00", "24:00")));
        UUID routingId = createRouting(tenant, productCode, resource.id());
        String orderNo = tag("SO-WI");
        orderService.create(new OrderCreateCommand(orderNo, OrderSource.MANUAL, productCode,
                new BigDecimal("10"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                OrderPriority.NORMAL, null, routingId, "C45 what-if", null));

        AgentWhatIfView view = agentWhatIfService.run(new AgentWhatIfRequest(whatIf,
                "如果把订单放到这个场景重排，和主计划差在哪？", Map.of("orderNos", List.of(orderNo))),
                "whatif:" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(view.available()).isTrue();
        assertThat(view.source()).isEqualTo("EVIDENCE");
        assertThat(view.solveStatus()).isEqualTo("SOLVED");
        assertThat(view.baselineSandboxId()).isEqualTo(baseline);
        assertThat(view.comparison()).isNotNull();
        assertThat(view.comparison().sandboxId()).isEqualTo(whatIf);
        assertThat(view.comparison().targetId()).isEqualTo(baseline);
        assertThat(view.comparison().addedTaskCount()).isPositive();
        assertThat(view.baselineTaskCount()).isZero();
        assertThat(view.afterTaskCount()).isPositive();
        assertThat(view.baselineEmpty()).isTrue();
        assertThat(view.comparisonWarnings())
                .containsExactly(AgentWhatIfService.WARNING_BASELINE_EMPTY);
        assertThat(view.impactedOrders()).contains(orderNo);
        assertThat(view.bottleneckChange()).isNotNull();
        assertThat(view.bottleneckChange().comparable()).isFalse();
        assertThat(view.bottleneckChange().notComparableReason())
                .isEqualTo("BASELINE_NO_RESOURCE_UTILIZATION");
        assertThat(view.bottleneckChange().changed()).isFalse();
        assertThat(view.answer()).contains("对比基线").contains("基线可比性警告");
        evidence("C45 whatif: solveTask=" + view.solveTaskId() + ", added="
                + view.comparison().addedTaskCount() + ", baselineTasks=" + view.baselineTaskCount()
                + ", afterTasks=" + view.afterTaskCount() + ", warnings=" + view.comparisonWarnings()
                + ", bottleneckComparable=" + view.bottleneckChange().comparable()
                + ", impacted=" + view.impactedOrders());
    }

    @Test
    @DisplayName("whatif：BASELINE 场景与未知参数分别返回 42200 并点名字段")
    void shouldRejectNonWhatIfSandboxAndUnsupportedParameters() {
        UUID tenant = newTenant();
        UUID baseline = newSandbox(tenant, "BASELINE", null);
        UUID whatIf = newSandbox(tenant, "WHAT_IF", baseline);

        assertThatThrownBy(() -> agentWhatIfService.run(new AgentWhatIfRequest(baseline,
                "直接改主计划", Map.of()), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("WHAT_IF")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);

        assertThatThrownBy(() -> agentWhatIfService.run(new AgentWhatIfRequest(whatIf,
                "未知参数", Map.of("overtimeRate", 1.5)), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("overtimeRate")
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNPROCESSABLE_ENTITY);
        evidence("C45 whatif boundary: BASELINE→42200, overtimeRate→42200");
    }

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                tenant, tag("C45-TEN-"), "C45 Agent 测试租户");
        tenants.add(tenant);
        TenantContext.setTenantId(tenant);
        return tenant;
    }

    private UUID newSandbox(UUID tenant, String type, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, parent_sandbox_id, snapshot)"
                        + " VALUES (?, ?, ?, ?, ?, '{}'::jsonb)",
                id, tenant, tag("C45-SB-") + type, type, parent);
        return id;
    }

    private UUID newMaterial(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                id, tenant, tag("C45-MAT-"), "C45 测试物料");
        return id;
    }

    private UUID newResource(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                        + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                id, tenant, tag("C45-RES-"), "C45 测试资源");
        return id;
    }

    private UUID newOrder(UUID tenant, UUID product, String orderNo, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date)"
                        + " VALUES (?, ?, ?, ?, 10, ?)",
                id, tenant, orderNo, product, dueDate);
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

    private long insertSolveTask(UUID tenant, UUID sandbox, String status, Long scoreHard, Long scoreSoft) {
        Long id = jdbc.queryForObject("""
                INSERT INTO solve_task (tenant_id, sandbox_id, engine, status, score_hard, score_soft, config)
                VALUES (?, ?, 'TIMEFOLD', ?, ?, ?, '{}'::jsonb)
                RETURNING id
                """, Long.class, tenant, sandbox, status, scoreHard, scoreSoft);
        return id == null ? 0L : id;
    }

    private UUID insertPlanVersion(UUID tenant, UUID sandbox) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,"
                        + " snapshot, task_count, created_by, updated_by)"
                        + " VALUES (?, ?, ?, ?, '1', 'DRAFT', '{}'::jsonb, 1, ?, ?)",
                id, tenant, sandbox, tag("C45-PLAN-"), TEST_OPERATOR, TEST_OPERATOR);
        return id;
    }

    private UUID insertJustification(UUID tenant, long solveTaskId, long taskId, String constraintId,
                                     String constraintName, String scoreImpact, Integer weight,
                                     BigDecimal deduction) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO constraint_justification
                    (id, tenant_id, solve_task_id, constraint_id, constraint_name,
                     score_impact, implicated_facts, explanation_zh, weight, deduction)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                id, tenant, solveTaskId, constraintId, constraintName, scoreImpact,
                "[{\"type\":\"TASK\",\"id\":" + taskId + "}]", "真库归因证据", weight, deduction);
        return id;
    }

    private UUID createRouting(UUID tenant, String productCode, UUID resourceId) {
        var routing = routingService.create(new RoutingCreateCommand(productCode, "V1",
                LocalDate.of(2026, 1, 1), null,
                List.of(new RoutingNodeCommand(10, null, "OP10", "切割", 600, 0,
                        null, resourceId, null, null, false),
                        new RoutingNodeCommand(20, null, "OP20", "装配", 1200, 0,
                                null, resourceId, null, null, false)),
                List.of(new RoutingEdgeCommand(10, 20, "FS", 0, null, null)), null));
        UUID routingId = routing.getId() instanceof UUID id ? id : UUID.fromString(String.valueOf(routing.getId()));
        routingService.activate(routingId);
        return routingId;
    }

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
