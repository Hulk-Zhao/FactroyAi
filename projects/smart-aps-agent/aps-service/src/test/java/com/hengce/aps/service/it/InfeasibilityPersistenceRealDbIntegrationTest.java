package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityReportRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityReportRepository.InfeasibilityReportRow;
import com.hengce.aps.service.schedule.SolveFinishEvent;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityPostProcessor;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityReportService;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * B2 持久化 + C51 读回真库验收：在 {@code aps_demo} 的只读副本上验证
 * {@code infeasibility_report} 的落库纪律与读取优先级。
 *
 * <p>只读副本库名由 {@code APS_TEST_DB_NAME} 指定（验收用 {@code aps_b2_verify}），
 * 不连接 {@code aps_demo} 本身；用例只改副本，副本用完即 DROP。</p>
 *
 * <p>覆盖：NO_EVIDENCE 的 {@code generated_at} 必须为 NULL、upsert 幂等、租户隔离、
 * 未命中现算不伪装持久化、以及有证据时的 C51 形状（枚举/<=8/可空规则）。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@TestPropertySource(properties = {"spring.flyway.enabled=false", "aps.mrp.blocking-enabled=false"})
class InfeasibilityPersistenceRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * 演示库中不存在的租户（只用于读，不触发外键写入）。
     */
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /**
     * aps_demo 中真实的 FAILED 任务（配置 DM-SO-1008，重放无结构性证据）。
     */
    private static final long DEMO_FAILED_TASK = 71L;

    private static final String ORIGINAL_SHIFT = "[{\"endTime\":\"20:00\",\"startTime\":\"08:00\","
            + "\"daysOfWeek\":[1,2,3,4,5,6]},{\"endTime\":\"08:00\",\"startTime\":\"20:00\","
            + "\"daysOfWeek\":[1,2,3,4,5]}]";

    private static final String COMPRESSED_SHIFT = "[{\"startTime\":\"08:00\",\"endTime\":\"09:00\","
            + "\"daysOfWeek\":[1,2,3,4,5,6,7]}]";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private InfeasibilityPostProcessor postProcessor;

    @Autowired
    private InfeasibilityReportService reportService;

    @Autowired
    private InfeasibilityReportRepository reportRepository;

    @BeforeEach
    void resetReportRow() {
        jdbc.update("DELETE FROM infeasibility_report WHERE tenant_id = ? AND solve_task_id = ?",
                TENANT, DEMO_FAILED_TASK);
    }

    @AfterEach
    void restoreDemoCalendar() {
        updateShift(ORIGINAL_SHIFT);
    }

    @Test
    void noEvidenceRowKeepsGeneratedAtNullAndUpsertIsIdempotent() throws Exception {
        SolveFinishEvent event = failedEvent();
        postProcessor.onSolveFinished(event);
        // 重复终态/重试：唯一键 + upsert，仍只一行。
        postProcessor.onSolveFinished(event);

        InfeasibilityReportRow row = reportRepository.find(TENANT, DEMO_FAILED_TASK).orElseThrow();
        assertEquals("NO_EVIDENCE", row.verdict(), "无结构性证明必须是 NO_EVIDENCE");
        assertNull(row.generatedAt(), "NO_EVIDENCE 的 generated_at 必须为 NULL，禁止 now() 兜底");
        assertNull(row.narrative(), "NO_EVIDENCE 不应有 narrative");
        assertEquals("DISABLED", row.llmStatus());
        assertEquals(1L, count("SELECT COUNT(*) FROM infeasibility_report "
                + "WHERE tenant_id = ? AND solve_task_id = ?", TENANT, DEMO_FAILED_TASK),
                "重复终态只能保留一行");

        JsonNode json = JSON.readTree(row.reportJson());
        assertTrue(json.get("generatedAt").isNull(), "report_json 中 generatedAt 也必须为 null");
        assertEquals(DEMO_FAILED_TASK, json.get("solveTaskId").asLong());
        assertEquals("NO_EVIDENCE", json.get("verdict").asText());
        assertEquals(0, json.get("blockingTasks").size());
        assertEquals(0, json.get("conflictPairs").size());
        assertEquals(0, json.get("resourceWindows").size());
        assertEquals(0, json.get("violatedRules").size());
        assertFalse(json.get("truncated").asBoolean());

        InfeasibilityReport readBack = reportService.report(TENANT, DEMO_FAILED_TASK);
        assertEquals(InfeasibilityVerdict.NO_EVIDENCE, readBack.verdict());
        assertNull(readBack.generatedAt(), "端点读回也必须保持 null");
        assertNull(readBack.solverRunId(), "事件不带 runId 且 metrics 无该字段时保持 null");
        assertTrue(readBack.assumptions().stream().noneMatch(a -> a.contains("查询时按")));
        evidence("[B2-persist] taskId=" + DEMO_FAILED_TASK + " verdict=" + row.verdict()
                + " generated_at=" + row.generatedAt() + " llm_status=" + row.llmStatus()
                + " rows=" + count("SELECT COUNT(*) FROM infeasibility_report WHERE tenant_id = ? "
                + "AND solve_task_id = ?", TENANT, DEMO_FAILED_TASK));
        evidence("[B2-readback] taskId=" + readBack.solveTaskId() + " verdict=" + readBack.verdict()
                + " generatedAt=" + readBack.generatedAt() + " solverRunId=" + readBack.solverRunId()
                + " truncated=" + readBack.truncated() + " narrative=" + readBack.narrative());
    }

    @Test
    void otherTenantCannotReadOrPersistVisibleToTenant() {
        persistFailedTask();
        assertTrue(reportRepository.find(OTHER_TENANT, DEMO_FAILED_TASK).isEmpty(),
                "另一租户不得读到本租户的落库报告");
        BizException exception = assertThrows(BizException.class,
                () -> reportService.report(OTHER_TENANT, DEMO_FAILED_TASK), "另一租户查任务应 404");
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        assertEquals(1L, count("SELECT COUNT(*) FROM infeasibility_report WHERE tenant_id = ? "
                + "AND solve_task_id = ?", TENANT, DEMO_FAILED_TASK));
        evidence("[B2-tenant] tenant=" + TENANT + " 有 1 行；tenant=" + OTHER_TENANT
                + " find 为空且端点返回 " + exception.getErrorCode().getCode());
    }

    @Test
    void computedPathMarksNotPersistedAndDoesNotFabricate() {
        InfeasibilityReport report = reportService.report(TENANT, DEMO_FAILED_TASK);
        assertEquals(InfeasibilityVerdict.NO_EVIDENCE, report.verdict(),
                "未改动的演示 FAILED 任务重放无结构性证明，只能 NO_EVIDENCE");
        assertNull(report.generatedAt());
        assertNull(report.solverRunId(), "metrics 无 runId 时不得编造");
        assertTrue(report.assumptions().stream().anyMatch(a -> a.contains("未落库")),
                "现算路径必须在 assumptions 标明未落库");
        assertEquals(0L, count("SELECT COUNT(*) FROM infeasibility_report "
                + "WHERE tenant_id = ? AND solve_task_id = ?", TENANT, DEMO_FAILED_TASK),
                "读取端点不得把现算结果写库");
        evidence("[B2-computed] taskId=" + report.solveTaskId() + " verdict=" + report.verdict()
                + " generatedAt=" + report.generatedAt() + " assumptions=" + report.assumptions());
    }

    @Test
    void persistedEvidenceShapeMatchesC51() throws Exception {
        compressCalendar();
        try {
            persistFailedTask();
            InfeasibilityReportRow row = reportRepository.find(TENANT, DEMO_FAILED_TASK).orElseThrow();
            assertEquals("NO_FEASIBLE_SOLUTION_FOUND", row.verdict());
            assertNotNull(row.generatedAt(), "有证据时必须落 generated_at");

            InfeasibilityReport report = reportService.report(TENANT, DEMO_FAILED_TASK);
            assertEquals(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND, report.verdict());
            assertNotNull(report.generatedAt());
            assertTrue(report.blockingTasks().size() <= 8);
            assertTrue(report.conflictPairs().size() <= 8);
            assertTrue(report.resourceWindows().size() <= 8);
            assertTrue(report.violatedRules().size() <= 8);
            assertTrue(report.blockingTasks().size() > 0, "压缩日历后必须有阻塞证据");
            assertTrue(report.narrative() != null && !report.narrative().isBlank());
            assertEquals(InfeasibilityReport.LlmStatus.DISABLED, report.llmStatus());
            assertEquals(List.of(), report.conflictPairs());

            JsonNode json = JSON.readTree(row.reportJson());
            assertFalse(json.get("generatedAt").isNull());
            evidence("[B2-shape] taskId=" + report.solveTaskId() + " verdict=" + report.verdict()
                    + " generatedAt=" + report.generatedAt() + " blocking=" + report.blockingTasks().size()
                    + " conflicts=" + report.conflictPairs().size() + " windows="
                    + report.resourceWindows().size() + " rules=" + report.violatedRules().size()
                    + " truncated=" + report.truncated() + " llmStatus=" + report.llmStatus());
        } finally {
            updateShift(ORIGINAL_SHIFT);
        }
    }

    private SolveFinishEvent failedEvent() {
        return new SolveFinishEvent(DEMO_FAILED_TASK, TENANT, "FAILED", null, null,
                Map.of("terminationReason", "ENGINE_MEMORY"), "aps_demo 真实 FAILED 任务（副本重放）");
    }

    private void persistFailedTask() {
        postProcessor.onSolveFinished(failedEvent());
    }

    private void compressCalendar() {
        updateShift(COMPRESSED_SHIFT);
    }

    private void updateShift(String shiftsJson) {
        jdbc.update("UPDATE calendar c SET shifts = ?::jsonb WHERE c.id IN ("
                + "SELECT DISTINCT res.calendar_id FROM routing_node rn "
                + "JOIN operation_template ot ON ot.id = rn.operation_template_id "
                + "JOIN resource res ON res.id = ot.primary_resource_id "
                + "WHERE rn.routing_id = (SELECT routing_id FROM orders WHERE order_no = ?))",
                shiftsJson, "DM-SO-1008");
    }
}
