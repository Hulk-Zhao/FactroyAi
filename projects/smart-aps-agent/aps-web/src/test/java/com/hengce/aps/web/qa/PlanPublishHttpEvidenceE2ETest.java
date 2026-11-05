package com.hengce.aps.web.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hengce.aps.web.security.JwtTokenService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M3-QA-04 两个后端缺陷的 HTTP 层证据（真库 + 全量上下文 + MockMvc）。
 *
 * <p>输出 {@code POST /api/v1/plans/{id}/publish} 的真实响应状态码与 {@code X-Trace-Id}：</p>
 * <ul>
 *   <li>缺陷一：按基准求解范围（B 客户）发布的 DRAFT，A 客户任务数为 0、{@code task_count} 等于 B 任务数；</li>
 *   <li>缺陷二：场景存在 {@code RUNNING} 求解任务时返回 422/42200 并指出 solveTaskId，终态后发布 200 且回填版本号。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@SpringBootTest(classes = QaE2eTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "aps.security.mode=prod",
        "aps.security.jwt.secret=M3-QA-04-e2e-test-secret-0123456789abcdef",
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
class PlanPublishHttpEvidenceE2ETest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ADMIN_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private static final String RUN_PREFIX = "QA4-" + Long.toHexString(System.currentTimeMillis()) + "-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String adminToken;

    private UUID sandboxId;

    private UUID materialId;

    private UUID resourceId;

    private final List<UUID> orderIds = new ArrayList<>();

    private final List<UUID> planIds = new ArrayList<>();

    private final List<Long> solveTaskIds = new ArrayList<>();

    @BeforeEach
    void bindPrincipal() {
        adminToken = jwtTokenService.issue(ADMIN_USER_ID, TENANT_ID, List.of("APS_USER"));
    }

    @AfterEach
    void cleanupRows() {
        try {
            if (jdbc == null) {
                return;
            }
            for (UUID planId : planIds) {
                jdbc.update("DELETE FROM task WHERE plan_version_id = ?", planId);
            }
            for (Long solveTaskId : solveTaskIds) {
                jdbc.update("DELETE FROM solve_task WHERE id = ?", solveTaskId);
            }
            if (sandboxId != null) {
                jdbc.update("DELETE FROM task WHERE sandbox_id = ?", sandboxId);
                jdbc.update("DELETE FROM plan_version WHERE sandbox_id = ?", sandboxId);
                jdbc.update("DELETE FROM solve_task WHERE sandbox_id = ?", sandboxId);
                jdbc.update("DELETE FROM sandbox WHERE id = ?", sandboxId);
            }
            for (UUID orderId : orderIds) {
                jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
            }
            if (materialId != null) {
                jdbc.update("DELETE FROM material WHERE id = ?", materialId);
            }
            if (resourceId != null) {
                jdbc.update("DELETE FROM resource WHERE id = ?", resourceId);
            }
        } finally {
            adminToken = null;
            sandboxId = null;
            materialId = null;
            resourceId = null;
            orderIds.clear();
            planIds.clear();
            solveTaskIds.clear();
        }
    }

    @Test
    @DisplayName("HTTP：B 范围 DRAFT 发布 200，A 任务 0 条、task_count=9，并记录 traceId")
    void scopedPublishOverHttpKeepsOtherCustomerOut() throws Exception {
        seedBase();
        UUID orderA1 = newOrder("SO-A-001", 5, 1);
        UUID orderA2 = newOrder("SO-A-002", 5, 6);
        UUID orderB1 = newOrder("SO-B-001", 4, 11);
        UUID orderB2 = newOrder("SO-B-002", 5, 15);
        String orderNoA1 = orderNo(orderA1);
        String orderNoA2 = orderNo(orderA2);
        String orderNoB1 = orderNo(orderB1);
        String orderNoB2 = orderNo(orderB2);
        UUID planId = insertDraft("PLAN-QA4-SCOPED", List.of(orderNoB1, orderNoB2));

        MvcResult result = publish(planId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");

        long aTasks = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.plan_version_id = ? AND o.order_no IN (?, ?)
                """, planId, orderNoA1, orderNoA2);
        long bTasks = count("""
                SELECT COUNT(1) FROM task t JOIN orders o ON o.id = t.order_id
                 WHERE t.plan_version_id = ? AND o.order_no IN (?, ?)
                """, planId, orderNoB1, orderNoB2);
        int taskCount = jdbc.queryForObject(
                "SELECT task_count FROM plan_version WHERE id = ?", Integer.class, planId);

        record("缺陷一 scoped publish", "POST /api/v1/plans/" + planId + "/publish",
                200, 0, traceId, "A tasks=" + aTasks + ", B tasks=" + bTasks
                        + ", plan.task_count=" + taskCount);

        assertThat(aTasks).isZero();
        assertThat(bTasks).isEqualTo(9);
        assertThat(taskCount).isEqualTo(9);
    }

    @Test
    @DisplayName("HTTP：RUNNING 时发布 422/42200（含 solveTaskId），终态后发布 200 且回填版本号")
    void publishWhileSolveRunningIsRejectedOverHttp() throws Exception {
        seedBase();
        UUID orderB = newOrder("SO-ACTIVE-B", 4, 1);
        String orderNoB = orderNo(orderB);
        UUID planId = insertDraft("PLAN-QA4-RUNNING", List.of(orderNoB));
        long runningSolveTaskId = insertSolveTask("RUNNING");

        MvcResult rejected = publish(planId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(42200))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("solveTaskId=" + runningSolveTaskId)))
                .andReturn();
        String rejectTraceId = rejected.getResponse().getHeader("X-Trace-Id");

        long boundWhileRunning = count(
                "SELECT COUNT(1) FROM task WHERE plan_version_id = ?", planId);
        String statusWhileRunning = jdbc.queryForObject(
                "SELECT status FROM plan_version WHERE id = ?", String.class, planId);
        record("缺陷二 publish while RUNNING", "POST /api/v1/plans/" + planId + "/publish",
                422, 42200, rejectTraceId, "solveTaskId=" + runningSolveTaskId
                        + ", plan.status=" + statusWhileRunning
                        + ", boundTasks=" + boundWhileRunning);

        assertThat(statusWhileRunning).isEqualTo("DRAFT");
        assertThat(boundWhileRunning).isZero();

        jdbc.update("UPDATE solve_task SET status = 'SOLVED', finished_at = now() WHERE id = ?",
                runningSolveTaskId);

        MvcResult published = publish(planId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String successTraceId = published.getResponse().getHeader("X-Trace-Id");

        long boundAfterSolved = count(
                "SELECT COUNT(1) FROM task WHERE plan_version_id = ?", planId);
        int taskCount = jdbc.queryForObject(
                "SELECT task_count FROM plan_version WHERE id = ?", Integer.class, planId);
        record("缺陷二 publish after SOLVED", "POST /api/v1/plans/" + planId + "/publish",
                200, 0, successTraceId, "boundTasks=" + boundAfterSolved
                        + ", plan.task_count=" + taskCount);

        assertThat(boundAfterSolved).isEqualTo(4);
        assertThat(taskCount).isEqualTo(4);
    }

    private org.springframework.test.web.servlet.ResultActions publish(UUID planId) throws Exception {
        return mockMvc.perform(post("/api/v1/plans/" + planId + "/publish")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"QA-04 HTTP 证据\"}"));
    }

    private void seedBase() {
        sandboxId = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot)"
                        + " VALUES (?, ?, ?, 'WHAT_IF', '{}'::jsonb)",
                sandboxId, TENANT_ID, RUN_PREFIX + "SB");
        materialId = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit)"
                        + " VALUES (?, ?, ?, ?, 'FINISHED', 'PCS')",
                materialId, TENANT_ID, RUN_PREFIX + "MAT", "QA-04 HTTP 证据物料");
        resourceId = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type, capacity_value)"
                        + " VALUES (?, ?, ?, ?, 'PRIMARY', 100)",
                resourceId, TENANT_ID, RUN_PREFIX + "RES", "QA-04 HTTP 证据资源");
    }

    private UUID newOrder(String orderNo, int taskCount, int startIndex) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date)"
                        + " VALUES (?, ?, ?, ?, 10, ?)",
                orderId, TENANT_ID, RUN_PREFIX + orderNo, materialId, LocalDate.of(2026, 11, 20));
        orderIds.add(orderId);
        for (int index = 0; index < taskCount; index++) {
            OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC)
                    .plusHours(startIndex + index);
            jdbc.update("""
                    INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, resource_id,
                                      start_time, end_time, qty, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 10, 'PLANNED')
                    """, TENANT_ID, orderId, sandboxId, (startIndex + index) * 10, resourceId,
                    start, start.plusHours(1));
        }
        return orderId;
    }

    private UUID insertDraft(String planNo, List<String> scopeOrderNos) {
        UUID planId = UUID.randomUUID();
        String scope = "{\"scopeOrderNos\":[\"" + String.join("\",\"", scopeOrderNos) + "\"]}";
        jdbc.update("""
                INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,
                                          snapshot, task_count, reason, created_by)
                VALUES (?, ?, ?, ?, '1', 'DRAFT', ?::jsonb, 0, 'QA-04 HTTP 证据', ?)
                """, planId, TENANT_ID, sandboxId, planNo, scope, ADMIN_USER_ID);
        planIds.add(planId);
        return planId;
    }

    private long insertSolveTask(String status) {
        Long id = jdbc.queryForObject("""
                INSERT INTO solve_task (tenant_id, sandbox_id, engine, status, started_at, config)
                VALUES (?, ?, 'TIMEFOLD', ?, now(), '{"parameters":{}}'::jsonb)
                RETURNING id
                """, Long.class, TENANT_ID, sandboxId, status);
        long solveTaskId = id == null ? 0L : id;
        solveTaskIds.add(solveTaskId);
        return solveTaskId;
    }

    private String orderNo(UUID orderId) {
        return jdbc.queryForObject("SELECT order_no FROM orders WHERE id = ?", String.class, orderId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static void record(String scenario, String endpoint, int http, int code, String traceId,
                               String db) {
        String line = scenario + " | " + endpoint + " | HTTP " + http + " | code=" + code
                + " | traceId=" + traceId + " | " + db + System.lineSeparator();
        try {
            Path target = Path.of("target", "qa-evidence", "m3-qa-04-publish-http.txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("写发布 HTTP 证据失败", exception);
        }
    }
}
