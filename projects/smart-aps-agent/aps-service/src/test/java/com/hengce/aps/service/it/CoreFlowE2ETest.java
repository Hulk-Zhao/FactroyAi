package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.model.FreezeStatus;
import com.hengce.aps.domain.model.OrderStatus;
import com.hengce.aps.service.masterdata.order.OrderPriority;
import com.hengce.aps.service.masterdata.order.OrderSource;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import com.hengce.aps.service.importexport.EntityDataExporter;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.mrp.InventoryItemCommand;
import com.hengce.aps.service.mrp.InventoryService;
import com.hengce.aps.service.masterdata.material.MaterialCreateCommand;
import com.hengce.aps.service.masterdata.order.OrderCreateCommand;
import com.hengce.aps.service.masterdata.order.OrderFreezeCommand;
import com.hengce.aps.service.masterdata.order.OrderService;
import com.hengce.aps.service.masterdata.order.OrderView;
import com.hengce.aps.service.masterdata.resource.ResourceView;
import com.hengce.aps.service.plan.PlanActionView;
import com.hengce.aps.service.process.routing.RoutingNodeCommand;
import com.hengce.aps.service.process.routing.RoutingService;
import com.hengce.aps.service.sandbox.SandboxApplyResultView;
import com.hengce.aps.service.sandbox.SandboxService;
import com.hengce.aps.service.sandbox.SandboxSummaryView;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * M3-QA-01 核心业务流程 E2E：主数据 → 订单状态机 → 求解 → 计划版本 → 导出。
 *
 * <p>跑在真库上（{@code APS_TEST_DB_ENABLED=true}），租户上下文由基座
 * {@code bindTestContext()} 显式注入，不依赖任何 HTTP 会话。求解引擎使用测试内联的
 * 确定性 {@link SolverAdapter} 替身（测试作用域没有 solver-timefold，且禁止改 pom），
 * 它严格返回「每道工序一条分配」，从而通过 {@code ScheduleService} 的完整性守卫。</p>
 *
 * <p>用例结束只清理本次创建的数据；场景行本身不删——{@code SandboxService.apply}
 * 会把它提升为租户 BASELINE，删掉会让后续回归缺少 BASELINE。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@TestPropertySource(properties = {"aps.solve.async-enabled=false", "aps.solve.fallback-enabled=true"})
@Import(CoreFlowE2ETest.DeterministicSolverConfiguration.class)
class CoreFlowE2ETest extends AbstractRealDbIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private RoutingService routingService;

    @Autowired
    private SolveTaskLifecycleService solveTaskLifecycleService;

    @Autowired
    private SandboxService sandboxService;

    @Autowired
    private EntityDataExporter entityDataExporter;

    @Autowired
    private InventoryService inventoryService;

    private String orderNo;

    private UUID sandboxId;

    private String routingProductCode;

    @AfterEach
    void cleanupCoreFlowRows() {
        try {
            if (jdbc == null) {
                return;
            }
            jdbc.update("DELETE FROM inventory WHERE tenant_id = ? AND warehouse_code = ?",
                    TenantIdHolder.TENANT_ID, "WH-E2E");
            if (sandboxId != null) {
                jdbc.update("DELETE FROM constraint_justification WHERE solve_task_id IN "
                        + "(SELECT id FROM solve_task WHERE sandbox_id = ?)", sandboxId);
                jdbc.update("DELETE FROM task WHERE sandbox_id = ?", sandboxId);
                jdbc.update("DELETE FROM solve_task WHERE sandbox_id = ?", sandboxId);
                jdbc.update("DELETE FROM plan_version WHERE sandbox_id = ?", sandboxId);
            }
            if (orderNo != null) {
                jdbc.update("DELETE FROM orders WHERE order_no = ?", orderNo);
            }
            if (routingProductCode != null) {
                jdbc.update("DELETE FROM routing_edge WHERE routing_id IN "
                        + "(SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id "
                        + "WHERE m.code = ?)", routingProductCode);
                jdbc.update("DELETE FROM routing_node WHERE routing_id IN "
                        + "(SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id "
                        + "WHERE m.code = ?)", routingProductCode);
                jdbc.update("DELETE FROM routing WHERE product_id IN "
                        + "(SELECT id FROM material WHERE code = ?)", routingProductCode);
            }
        } finally {
            orderNo = null;
            sandboxId = null;
            routingProductCode = null;
        }
    }

    @Test
    @DisplayName("订单 确认→冻结→释放→开工→求解→计划版本→导出 xlsx 全链路")
    void coreFlowFromOrderToExportedPlan() {
        String productCode = tag("FG-P");
        String componentCode = tag("RM-1");

        // 1. 主数据：物料 / BOM / 资源 / 日历 / 工艺路线
        materialService.create(new MaterialCreateCommand(productCode, "QAE2E 成品", MaterialType.FINISHED,
                null, "PCS", null, null, null, null, null, null, null));
        materialService.create(new MaterialCreateCommand(componentCode, "QAE2E 子件", MaterialType.RAW,
                null, "PCS", null, null, null, null, null, null, null));
        activate(createBom(productCode, "V1", componentCode));
        // C25：全量求解与增量同口径，齐套未通过会返回 42200，因此先备足子件库存。
        inventoryService.upsert(List.of(new InventoryItemCommand(componentCode, "WH-E2E",
                new BigDecimal("100"), BigDecimal.ZERO, OffsetDateTime.now(ZoneOffset.UTC))));
        ResourceView resource = createResource(tag("RES-1"), CapacityMode.UNIT_PER_HOUR,
                new BigDecimal("10"), null);
        createCalendar(tag("CAL-F"), 1, LocalDate.of(2026, 1, 1), null, "FACTORY", null,
                List.of(shift("00:00", "24:00")));
        routingProductCode = productCode;
        UUID routingId = createRouting(productCode, resource.id());

        // 2. 订单：创建 → 确认 → 冻结 → 释放 → 开工
        orderNo = tag("SO-1");
        OrderView order = orderService.create(new OrderCreateCommand(orderNo, OrderSource.MANUAL,
                productCode, new BigDecimal("10"), LocalDate.now(ZoneOffset.UTC).plusDays(7),
                OrderPriority.NORMAL, null, routingId, "M3-QA-01 E2E", null));
        assertEquals(OrderStatus.DRAFT, order.status(), "创建后应为 DRAFT");
        OrderView confirmed = orderService.confirm(orderNo);
        assertEquals(OrderStatus.CONFIRMED, confirmed.status(), "确认后应为 CONFIRMED");
        OrderView frozen = orderService.freeze(orderNo,
                new OrderFreezeCommand(FreezeStatus.FROZEN, null, null, null, confirmed.version()));
        assertEquals(FreezeStatus.FROZEN, frozen.freezeStatus(), "冻结后 freezeStatus 应为 FROZEN");
        OrderView released = orderService.release(orderNo, "M3-QA-01 释放", frozen.version());
        assertEquals(OrderStatus.RELEASED, released.status(), "释放后应为 RELEASED");
        OrderView started = orderService.start(orderNo, released.version());
        assertEquals(OrderStatus.IN_PROGRESS, started.status(), "开工后应为 IN_PROGRESS");

        // 3. 场景 + 求解（同步执行）
        SandboxSummaryView sandbox = sandboxService.create(tag("SB-1"), "WHAT_IF", null, null);
        sandboxId = sandbox.id();
        SolveTaskDetail solved = solveTaskLifecycleService.submit(new SolveRequest(
                tag("TASK-1"), sandboxId, null, null, List.of(orderNo), Map.of()), null);

        assertEquals(SolveTaskStatus.SOLVED.name(), solved.status(), "求解终态必须为 SOLVED");
        assertEquals(0L, solved.scoreHard(), "score_hard 必须为 0");
        assertNotNull(solved.metrics(), "metrics 不应为空");
        assertEquals(0, ((Number) solved.metrics().get("unassignedTaskCount")).intValue(),
                "unassignedTaskCount 必须为 0");
        long taskRows = count("SELECT count(*) FROM task WHERE sandbox_id = ? AND deleted = false",
                sandboxId);
        assertEquals(2L, taskRows, "两道路线节点应各落一条 task");
        evidence("solve: status=" + solved.status() + ", scoreHard=" + solved.scoreHard()
                + ", unassignedTaskCount=" + solved.metrics().get("unassignedTaskCount")
                + ", task=" + taskRows);

        // 4. 计划版本落库（apply 发布 plan_version）
        SandboxApplyResultView applied = sandboxService.apply(sandboxId, "M3-QA-01 发布");
        PlanActionView plan = applied.plan();
        assertNotNull(plan, "apply 必须产出计划动作");
        assertEquals("PUBLISHED", plan.status(), "计划版本必须为 PUBLISHED");
        long publishedRows = count("SELECT count(*) FROM plan_version WHERE sandbox_id = ? "
                + "AND status = 'PUBLISHED' AND deleted = false", sandboxId);
        assertEquals(1L, publishedRows, "同一场景应只有一条 PUBLISHED plan_version");
        assertEquals(2, plan.taskCount(), "计划版本快照任务数应与落库 task 数一致");
        evidence("plan: planNo=" + plan.planNo() + ", version=" + plan.version()
                + ", status=" + plan.status() + ", taskCount=" + plan.taskCount());

        // 5. 导出 orders xlsx，行数必须与库内一致
        byte[] xlsx = entityDataExporter.export(ImportEntity.ORDERS);
        assertTrue(xlsx.length > 0, "导出内容不应为空");
        int exportedRows = countDataRows(xlsx);
        long dbOrders = count("SELECT count(*) FROM orders WHERE tenant_id = ? AND deleted = false",
                TenantIdHolder.TENANT_ID);
        assertEquals(dbOrders, (long) exportedRows, "导出 orders 行数必须与库内订单数一致");
        evidence("export: orders xlsx rows=" + exportedRows + ", dbOrders=" + dbOrders);
    }

    private UUID createRouting(String productCode, UUID resourceId) {
        var routing = routingService.create(new com.hengce.aps.service.process.routing.RoutingCreateCommand(
                productCode, "V1", LocalDate.of(2026, 1, 1), null,
                List.of(new RoutingNodeCommand(10, null, "OP10", "切割", 600, 0,
                        null, resourceId, null, null, false),
                        new RoutingNodeCommand(20, null, "OP20", "装配", 1200, 0,
                                null, resourceId, null, null, false)),
                List.of(new com.hengce.aps.service.process.routing.RoutingEdgeCommand(
                        10, 20, "FS", 0, null, null)), null));
        routingService.activate(uuidOf(routing.getId()));
        return uuidOf(routing.getId());
    }

    private static UUID uuidOf(Object id) {
        return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
    }

    private static int countDataRows(byte[] xlsx) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            int rows = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                if (row.getCell(0) != null && !String.valueOf(row.getCell(0)).isBlank()) {
                    rows++;
                }
            }
            return rows;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("解析导出 xlsx 失败", exception);
        }
    }

    /**
     * 租户常量持有者：避免与其它测试类的静态导入冲突。
     */
    private static final class TenantIdHolder {
        private static final UUID TENANT_ID =
                UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    /**
     * 确定性求解替身：按订单工序逐条产出分配，保证完整性守卫通过。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicSolverConfiguration {

        @Bean
        SolverAdapter deterministicSolverAdapter() {
            return new DeterministicSolverAdapter();
        }
    }

    /**
     * 测试替身：不调用任何真实引擎，只按工序数产出可落库的分配。
     */
    static final class DeterministicSolverAdapter implements SolverAdapter {

        @Override
        public SolverEngine engine() {
            return SolverEngine.TIMEFOLD;
        }

        @Override
        public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
            OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
                    .plusMinutes(5);
            Map<UUID, OffsetDateTime> resourceFree = new HashMap<>();
            Map<UUID, Integer> resourceSequence = new HashMap<>();
            List<TaskAssignment> assignments = new ArrayList<>();
            for (SchedulingOrder order : problem.orders()) {
                OffsetDateTime orderFree = base;
                for (SchedulingOperation operation : order.operations()) {
                    UUID resourceId = resolveResource(operation, problem.resources());
                    OffsetDateTime resourceStart = resourceFree.getOrDefault(resourceId, base);
                    OffsetDateTime start = resourceStart.isAfter(orderFree) ? resourceStart : orderFree;
                    long seconds = runSeconds(operation, order.qty());
                    OffsetDateTime end = start.plusSeconds(seconds);
                    resourceFree.put(resourceId, end.plusMinutes(1));
                    orderFree = end;
                    int sequence = resourceSequence.merge(resourceId, 1, Integer::sum);
                    assignments.add(new TaskAssignment(null, order.orderId(), order.orderNo(),
                            operation.operationIndex(), operation.operationName(),
                            operation.operationTemplateId(), resourceId, codeOf(resourceId, problem.resources()),
                            operation.secondaryResourceIds(), start, start, start, end,
                            order.qty(), sequence, "PLANNED", false, 1));
                }
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("unassignedTaskCount", 0);
            extra.put("calendarFallback", false);
            extra.put("dependencyFallback", false);
            extra.put("setupMatrixFallback", false);
            return new ScheduleSolution("SOLVED",
                    new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                    assignments, List.of(),
                    new SolveMetrics(0L, "TERMINATED", List.of(), assignments.size(), extra),
                    "M3-QA-01 确定性求解替身");
        }

        private static UUID resolveResource(SchedulingOperation operation,
                                            List<SchedulingResource> resources) {
            if (operation.primaryResourceId() != null) {
                return operation.primaryResourceId();
            }
            if (resources.isEmpty()) {
                throw new IllegalStateException("测试替身需要至少一个资源");
            }
            return resources.get(0).resourceId();
        }

        private static String codeOf(UUID resourceId, List<SchedulingResource> resources) {
            return resources.stream().filter(resource -> resource.resourceId().equals(resourceId))
                    .map(SchedulingResource::code).findFirst().orElse(null);
        }

        private static long runSeconds(SchedulingOperation operation, BigDecimal qty) {
            BigDecimal perUnit = operation.standardSeconds() == null
                    ? BigDecimal.ONE : operation.standardSeconds();
            BigDecimal quantity = qty == null ? BigDecimal.ONE : qty;
            long value = perUnit.multiply(quantity).longValue();
            return value <= 0 ? 60L : value;
        }
    }
}
