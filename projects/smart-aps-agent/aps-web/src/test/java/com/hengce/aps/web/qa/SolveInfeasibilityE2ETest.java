package com.hengce.aps.web.qa;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hengce.aps.service.schedule.SolveFinishEvent;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityPostProcessor;
import com.hengce.aps.web.security.JwtTokenService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * C51 的不行证据端点真库 E2E：{@code GET /api/v1/solve-tasks/{id}/infeasibility}。
 *
 * <p>跑在 {@code aps_demo} 的只读副本（{@code APS_TEST_DB_NAME=aps_b2_verify}）上：
 * 用真实 FAILED 任务 71 触发 B1 归因后置处理器落库，再打真实 HTTP 端点读回，
 * 校验 C51 形状（枚举、可空规则、每类 <= 8）。同时覆盖未命中现算与 404。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@SpringBootTest(classes = QaE2eTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aps.security.mode=prod",
        "aps.security.jwt.secret=M3-QA-01-e2e-test-secret-0123456789abcdef",
        "aps.security.rate-limit.enabled=false",
        "aps.security.websocket.allowed-origins=http://localhost:5173",
        "aps.notification.scheduler.enabled=false",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:../db/migration",
        "mybatis-plus.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis-plus.configuration.map-underscore-to-camel-case=true",
        "mybatis-plus.configuration.cache-enabled=false",
        "mybatis-plus.global-config.banner=false",
        "mybatis-plus.global-config.db-config.id-type=input",
        "mybatis-plus.global-config.db-config.logic-delete-field=deleted",
        "mybatis-plus.global-config.db-config.logic-delete-value=true",
        "mybatis-plus.global-config.db-config.logic-not-delete-value=false"
})
class SolveInfeasibilityE2ETest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ADMIN_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private static final long DEMO_FAILED_TASK = 71L;

    private static final String ORIGINAL_SHIFT = "[{\"endTime\":\"20:00\",\"startTime\":\"08:00\","
            + "\"daysOfWeek\":[1,2,3,4,5,6]},{\"endTime\":\"08:00\",\"startTime\":\"20:00\","
            + "\"daysOfWeek\":[1,2,3,4,5]}]";

    private static final String COMPRESSED_SHIFT = "[{\"startTime\":\"08:00\",\"endTime\":\"09:00\","
            + "\"daysOfWeek\":[1,2,3,4,5,6,7]}]";

    private static final Path EVIDENCE_FILE =
            Path.of("target", "qa-evidence", "b2-c51-infeasibility.txt");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private InfeasibilityPostProcessor postProcessor;

    private String adminToken;

    @BeforeEach
    void bindPrincipal() {
        adminToken = jwtTokenService.issue(ADMIN_USER_ID, TENANT, List.of("APS_USER"));
        jdbc.update("DELETE FROM infeasibility_report WHERE tenant_id = ? AND solve_task_id = ?",
                TENANT, DEMO_FAILED_TASK);
    }

    @AfterEach
    void cleanup() {
        updateShift(ORIGINAL_SHIFT);
        jdbc.update("DELETE FROM infeasibility_report WHERE tenant_id = ? AND solve_task_id = ?",
                TENANT, DEMO_FAILED_TASK);
        adminToken = null;
    }

    @Test
    void persistedNoEvidenceIsReadBackWithNullGeneratedAt() throws Exception {
        postProcessor.onSolveFinished(failedEvent());

        MvcResult result = mockMvc.perform(get("/api/v1/solve-tasks/{id}/infeasibility", DEMO_FAILED_TASK)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.solveTaskId").value(DEMO_FAILED_TASK))
                .andExpect(jsonPath("$.data.verdict").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.generatedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.narrative").value(nullValue()))
                .andExpect(jsonPath("$.data.solverRunId").value(nullValue()))
                .andExpect(jsonPath("$.data.llmStatus").value("DISABLED"))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.blockingTasks", hasSize(0)))
                .andExpect(jsonPath("$.data.conflictPairs", hasSize(0)))
                .andExpect(jsonPath("$.data.resourceWindows", hasSize(0)))
                .andExpect(jsonPath("$.data.violatedRules", hasSize(0)))
                .andReturn();
        record("真实 FAILED 任务落库后端点读回", result);
    }

    @Test
    void missComputesOnDemandWithoutPretendingPersistence() throws Exception {
        assertEquals(0L, countRows(), "前置：本用例开始时应无落库行");

        MvcResult result = mockMvc.perform(get("/api/v1/solve-tasks/{id}/infeasibility", DEMO_FAILED_TASK)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.generatedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.blockingTasks", hasSize(0)))
                .andExpect(jsonPath("$.data.assumptions", hasItem(containsString("未落库"))))
                .andReturn();
        assertEquals(0L, countRows(), "读取端点不得把现算结果写库");
        record("未命中现算（未落库）", result);
    }

    @Test
    void unknownTaskReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/solve-tasks/{id}/infeasibility", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        record("不存在任务", null);
    }

    @Test
    void persistedEvidenceShapeSatisfiesC51() throws Exception {
        updateShift(COMPRESSED_SHIFT);
        try {
            postProcessor.onSolveFinished(failedEvent());
            MvcResult result = mockMvc.perform(get("/api/v1/solve-tasks/{id}/infeasibility", DEMO_FAILED_TASK)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.verdict").value("NO_FEASIBLE_SOLUTION_FOUND"))
                    .andExpect(jsonPath("$.data.generatedAt").value(notNullValue()))
                    .andExpect(jsonPath("$.data.llmStatus").value("DISABLED"))
                    .andExpect(jsonPath("$.data.blockingTasks", hasSize(lessThanOrEqualTo(8))))
                    .andExpect(jsonPath("$.data.conflictPairs", hasSize(lessThanOrEqualTo(8))))
                    .andExpect(jsonPath("$.data.resourceWindows", hasSize(lessThanOrEqualTo(8))))
                    .andExpect(jsonPath("$.data.violatedRules", hasSize(lessThanOrEqualTo(8))))
                    .andReturn();
            String payload = body(result);
            assertTrue(payload.contains("\"reasonCode\""), "阻塞证据应带确定性原因码");
            record("压缩日历后的有证据报告", result);
        } finally {
            updateShift(ORIGINAL_SHIFT);
        }
    }

    private SolveFinishEvent failedEvent() {
        return new SolveFinishEvent(DEMO_FAILED_TASK, TENANT, "FAILED", null, null,
                Map.of("terminationReason", "ENGINE_MEMORY"), "aps_demo 真实 FAILED 任务（副本重放）");
    }

    private long countRows() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM infeasibility_report "
                + "WHERE tenant_id = ? AND solve_task_id = ?", Long.class, TENANT, DEMO_FAILED_TASK);
        return value == null ? 0L : value;
    }

    private void updateShift(String shiftsJson) {
        jdbc.update("UPDATE calendar c SET shifts = ?::jsonb WHERE c.id IN ("
                + "SELECT DISTINCT res.calendar_id FROM routing_node rn "
                + "JOIN operation_template ot ON ot.id = rn.operation_template_id "
                + "JOIN resource res ON res.id = ot.primary_resource_id "
                + "WHERE rn.routing_id = (SELECT routing_id FROM orders WHERE order_no = ?))",
                shiftsJson, "DM-SO-1008");
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static void record(String label, MvcResult result) {
        String content = result == null ? "<无响应体>" : body(result);
        String line = "[" + label + "] HTTP " + (result == null ? 404
                : result.getResponse().getStatus()) + System.lineSeparator() + content
                + System.lineSeparator();
        System.out.println("[B2-C51] " + line);
        try {
            Files.createDirectories(EVIDENCE_FILE.getParent());
            Files.writeString(EVIDENCE_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("写 C51 证据失败", exception);
        }
    }
}
