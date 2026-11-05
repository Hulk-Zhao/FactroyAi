package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalysis;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalyzer;
import com.hengce.aps.service.schedule.infeasibility.RepairCandidate;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * B1 归因内核真库验收：在 {@code aps_demo} 的只读副本上重放真实失败任务。
 *
 * <p>两条断言对应 B1 的判定纪律：</p>
 * <ol>
 *   <li>原样重放「引擎内存不足导致 FAILED」的任务，没有结构性证明，
 *       绝不能被报成 {@link InfeasibilityVerdict#NO_FEASIBLE_SOLUTION_FOUND}；</li>
 *   <li>在副本上把该订单所用资源的日历压缩为每天一小时，重放必须给出
 *       {@code NO_FEASIBLE_SOLUTION_FOUND}，并带可复算的阻塞证据。</li>
 * </ol>
 *
 * <p>只读副本库名由 {@code APS_TEST_DB_NAME} 指定（验收用 {@code aps_b1_verify}），
 * 不连接 {@code aps_demo} 本身；副本用完即 DROP。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@TestPropertySource(properties = {"spring.flyway.enabled=false", "aps.mrp.blocking-enabled=false"})
class InfeasibilityRealDbVerificationTest extends AbstractRealDbIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String COMPRESSED_SHIFT = "[{\"startTime\":\"08:00\",\"endTime\":\"09:00\","
            + "\"daysOfWeek\":[1,2,3,4,5,6,7]}]";

    private static final String ORIGINAL_SHIFT = "[{\"endTime\":\"20:00\",\"startTime\":\"08:00\","
            + "\"daysOfWeek\":[1,2,3,4,5,6]},{\"endTime\":\"08:00\",\"startTime\":\"20:00\","
            + "\"daysOfWeek\":[1,2,3,4,5]}]";

    @Autowired
    private InfeasibilityAnalyzer analyzer;

    @Test
    void failedWithoutProofIsNotInfeasibleAndCompressedCalendarIs() {
        long solveTaskId = Long.getLong("b1.solveTaskId", 71L);
        String orderNo = System.getProperty("b1.orderNo", "DM-SO-1008");
        TenantContext.setTenantId(TENANT);

        InfeasibilityAnalysis baseline = analyzer.analyzeTask(TENANT, solveTaskId);
        dump("baseline(未改动副本)", solveTaskId, baseline);
        assertNotEquals(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND, baseline.report().verdict(),
                "预算/资源耗尽导致的 FAILED 没有结构性证明，不得报 NO_FEASIBLE_SOLUTION_FOUND");

        // 只读副本内聚焦回放题面：清掉该场景其它订单的既有任务，只保留 solve_task 71 的请求订单。
        UUID demoSandbox = UUID.fromString("00000000-0000-0000-0000-000000000401");
        jdbc.update("DELETE FROM task_adjustment WHERE task_id IN "
                + "(SELECT id FROM task WHERE sandbox_id = ?)", demoSandbox);
        int removedTasks = jdbc.update("DELETE FROM task WHERE sandbox_id = ?", demoSandbox);
        evidence("[B1-SQL] 副本清理既有任务行数=" + removedTasks + "（sandbox=" + demoSandbox + "）");

        int updated = jdbc.update("UPDATE calendar c SET shifts = ?::jsonb WHERE c.id IN ("
                + "SELECT DISTINCT res.calendar_id FROM routing_node rn "
                + "JOIN operation_template ot ON ot.id = rn.operation_template_id "
                + "JOIN resource res ON res.id = ot.primary_resource_id "
                + "WHERE rn.routing_id = (SELECT routing_id FROM orders WHERE order_no = ?))",
                COMPRESSED_SHIFT, orderNo);
        evidence("[B1-SQL] 压缩日历行数=" + updated + "（orderNo=" + orderNo + "，只改只读副本 aps_b1_verify）");
        assertTrue(updated > 0, "副本中必须能按订单工艺路线找到待压缩的日历");

        InfeasibilityAnalysis mutated = analyzer.analyzeTask(TENANT, solveTaskId);
        dump("window(副本日历压缩为08:00-09:00)", solveTaskId, mutated);

        InfeasibilityReport report = mutated.report();
        assertEquals(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND, report.verdict(),
                "单个工序在视野内找不到整块可用窗口时，必然无可行解");
        assertNotNull(report.generatedAt(), "非 NO_EVIDENCE 必须带 generatedAt");
        assertTrue(report.blockingTasks().size() > 0, "必须有阻塞任务证据");
        assertTrue(report.blockingTasks().stream()
                        .anyMatch(task -> task.reasonCode()
                                == com.hengce.aps.solver.api.infeasibility.InfeasibilityReasonCode
                                .RESOURCE_WINDOW_SHORT),
                "必须给出 RESOURCE_WINDOW_SHORT 原因码");
        assertTrue(report.blockingTasks().size() <= 8, "每类证据最多 8 条");

        // 第三个场景：恢复真实日历，构造两个冻结任务在同一资源上重叠，验证冲突对与冻结证明。
        jdbc.update("UPDATE calendar c SET shifts = ?::jsonb WHERE c.id IN ("
                + "SELECT DISTINCT res.calendar_id FROM routing_node rn "
                + "JOIN operation_template ot ON ot.id = rn.operation_template_id "
                + "JOIN resource res ON res.id = ot.primary_resource_id "
                + "WHERE rn.routing_id = (SELECT routing_id FROM orders WHERE order_no = ?))",
                ORIGINAL_SHIFT, orderNo);
        UUID conflictSandbox = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type) VALUES (?, ?, ?, 'WHAT_IF')",
                conflictSandbox, TENANT, "B1-CONFLICT-" + conflictSandbox);
        Long conflictSolveTaskId = jdbc.queryForObject(
                "INSERT INTO solve_task (tenant_id, sandbox_id, status, config) "
                        + "VALUES (?, ?, 'FAILED', ?::jsonb) RETURNING id",
                Long.class, TENANT, conflictSandbox,
                "{\"orderNos\":[\"DM-SO-1008\"]}");
        UUID smt = UUID.fromString("e1180000-0000-0000-0000-000000000001");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0)
                .plusHours(16);
        insertFrozenTask(conflictSandbox, orderId("DM-SO-1001"), smt, start, start.plusHours(3));
        insertFrozenTask(conflictSandbox, orderId("DM-SO-1002"), smt, start.plusHours(1), start.plusHours(4));
        evidence("[B1-SQL] 冲突场景 solveTaskId=" + conflictSolveTaskId + ", sandbox=" + conflictSandbox
                + ", 两条冻结任务在 RES-SMT-01 上重叠 2h");

        assertNotNull(conflictSolveTaskId, "冲突场景 solve_task 必须插入成功");
        InfeasibilityAnalysis conflict = analyzer.analyzeTask(TENANT, conflictSolveTaskId);
        dump("conflict(冻结任务重叠)", conflictSolveTaskId, conflict);
        assertEquals(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND, conflict.report().verdict(),
                "两个不可移动任务在同一资源上重叠必然违约");
        assertTrue(conflict.report().conflictPairs().size() >= 1, "必须给出冲突对");
        assertTrue(conflict.report().blockingTasks().stream()
                        .anyMatch(task -> task.reasonCode()
                                == com.hengce.aps.solver.api.infeasibility.InfeasibilityReasonCode
                                .FROZEN_CONFLICT),
                "必须给出 FROZEN_CONFLICT 原因码");
    }

    private UUID orderId(String orderNo) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", UUID.class, orderNo);
    }

    private void insertFrozenTask(UUID sandboxId, UUID orderId, UUID resourceId,
                                  OffsetDateTime start, OffsetDateTime end) {
        jdbc.update("INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, resource_id, "
                        + "start_time, end_time, setup_start_time, setup_end_time, status) "
                        + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 'PLANNED')",
                TENANT, orderId, sandboxId, resourceId, start, end, start, start);
    }

    private void dump(String label, long solveTaskId, InfeasibilityAnalysis analysis) {
        InfeasibilityReport report = analysis.report();
        evidence("[B1-" + label + "] solveTaskId=" + solveTaskId + ", verdict=" + report.verdict()
                + ", generatedAt=" + report.generatedAt() + ", llmStatus=" + report.llmStatus()
                + ", truncated=" + report.truncated() + ", candidateSeed=" + report.candidateSeed()
                + ", narrative=" + report.narrative());
        for (InfeasibilityReport.BlockingTask task : report.blockingTasks()) {
            evidence("[B1-" + label + "] blocking taskId=" + task.taskId() + ", orderNo=" + task.orderNo()
                    + ", operation=" + task.operation() + ", operationIndex=" + task.operationIndex()
                    + ", reasonCode=" + task.reasonCode() + ", reason=" + task.reason());
        }
        for (InfeasibilityReport.ConflictPair pair : report.conflictPairs()) {
            evidence("[B1-" + label + "] conflict taskA=" + pair.taskA() + ", taskB=" + pair.taskB()
                    + ", resourceId=" + pair.resourceId() + ", overlap=" + pair.overlapStart()
                    + " ~ " + pair.overlapEnd());
        }
        for (InfeasibilityReport.ResourceWindow window : report.resourceWindows()) {
            evidence("[B1-" + label + "] window resourceId=" + window.resourceId()
                    + ", window=" + window.windowStart() + " ~ " + window.windowEnd()
                    + ", requiredHours=" + window.requiredHours() + ", gapHours=" + window.gapHours());
        }
        for (InfeasibilityReport.ViolatedRule rule : report.violatedRules()) {
            evidence("[B1-" + label + "] rule code=" + rule.ruleCode() + ", name=" + rule.constraintName()
                    + ", deduction=" + rule.deduction() + ", count=" + rule.count());
        }
        for (String assumption : report.assumptions()) {
            evidence("[B1-" + label + "] assumption " + assumption);
        }
        List<RepairCandidate> repairs = analysis.repairCandidates();
        evidence("[B1-" + label + "] repairCandidates=" + repairs.size());
        for (RepairCandidate repair : repairs) {
            evidence("[B1-" + label + "] repair kind=" + repair.kind() + ", rank=" + repair.rank()
                    + ", reasonCode=" + repair.reasonCode() + ", taskIds=" + repair.taskIds()
                    + ", params=" + repair.params() + ", rationale=" + repair.rationale()
                    + ", generatedAt=" + OffsetDateTime.now(ZoneOffset.UTC));
        }
    }
}
