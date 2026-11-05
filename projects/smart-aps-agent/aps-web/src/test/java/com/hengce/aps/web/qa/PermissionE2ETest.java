package com.hengce.aps.web.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hengce.aps.web.security.JwtTokenService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * M3-QA-01 权限 E2E（真库 + 全量上下文 + MockMvc）。
 *
 * <p>用 V1 种子角色（ADMIN/PLANNER/VIEWER）真实落库的用户打同一个写端点
 * {@code POST /api/v1/materials}（{@code @RequiresPermission("MD_WRITE")}）：
 * ADMIN 放行、PLANNER/VIEWER 由 {@code PermissionInterceptor} 返回 403/40300、
 * 匿名由 {@code ApiSecurityFilter} 直接返回 401/40100。令牌由 {@link JwtTokenService}
 * 直接签发（生产同款 HS256），不依赖登录流程。</p>
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
class PermissionE2ETest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ADMIN_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private static final UUID PLANNER_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    private static final UUID VIEWER_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");

    private static final String RUN_PREFIX = "QA9-" + Long.toHexString(System.currentTimeMillis()) + "-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService jwtTokenService;

    private UUID plannerId;

    private UUID viewerId;

    private String adminToken;

    private String plannerToken;

    private String viewerToken;

    @BeforeEach
    void bindPrincipals() {
        plannerId = createUser("planner", PLANNER_ROLE_ID);
        viewerId = createUser("viewer", VIEWER_ROLE_ID);
        adminToken = jwtTokenService.issue(ADMIN_USER_ID, TENANT_ID, List.of("APS_USER"));
        plannerToken = jwtTokenService.issue(plannerId, TENANT_ID, List.of("APS_USER"));
        viewerToken = jwtTokenService.issue(viewerId, TENANT_ID, List.of("APS_USER"));
    }

    @AfterEach
    void cleanupRows() {
        try {
            if (jdbc == null) {
                return;
            }
            jdbc.update("DELETE FROM material WHERE code LIKE ?", RUN_PREFIX + "%");
            if (plannerId != null) {
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", plannerId);
                jdbc.update("DELETE FROM users WHERE id = ?", plannerId);
            }
            if (viewerId != null) {
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", viewerId);
                jdbc.update("DELETE FROM users WHERE id = ?", viewerId);
            }
        } finally {
            plannerId = null;
            viewerId = null;
            adminToken = null;
            plannerToken = null;
            viewerToken = null;
        }
    }

    @Test
    @DisplayName("ADMIN 有 MD_WRITE：POST /materials 200")
    void adminWithMdWriteIsAllowed() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialJson(RUN_PREFIX + "ADMIN-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        record("ADMIN", "POST /api/v1/materials", "200", "0", result);
    }

    @Test
    @DisplayName("PLANNER 无 MD_WRITE：POST /materials 403/40300")
    void plannerWithoutMdWriteIsForbidden() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + plannerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialJson(RUN_PREFIX + "PLANNER-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andReturn();
        assertEquals(40300, bodyCode(result), "无权限必须返回业务码 40300");
        record("PLANNER", "POST /api/v1/materials", "403", "40300", result);
    }

    @Test
    @DisplayName("VIEWER 只读：写端点 403/40300，读端点 200")
    void viewerCanReadButCannotWrite() throws Exception {
        MvcResult write = mockMvc.perform(post("/api/v1/materials")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialJson(RUN_PREFIX + "VIEWER-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andReturn();
        record("VIEWER", "POST /api/v1/materials", "403", "40300", write);

        MvcResult read = mockMvc.perform(get("/api/v1/materials")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        record("VIEWER", "GET /api/v1/materials", "200", "0", read);
    }

    @Test
    @DisplayName("匿名无 token：写端点 401/40100")
    void anonymousWriteIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(materialJson(RUN_PREFIX + "ANON-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andReturn();
        assertEquals(40100, bodyCode(result), "匿名写端点必须返回业务码 40100");
        record("ANONYMOUS", "POST /api/v1/materials", "401", "40100", result);
    }

    private UUID createUser(String suffix, UUID roleId) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, username, display_name, password_hash, "
                        + "email, status) VALUES (?, ?, ?, ?, NULL, NULL, 'ACTIVE')",
                userId, TENANT_ID, RUN_PREFIX + suffix, "QA 权限用户 " + suffix);
        jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        return userId;
    }

    private static String materialJson(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"QA 权限物料\",\"type\":\"RAW\",\"unit\":\"PCS\"}";
    }

    private static int bodyCode(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private static void record(String role, String endpoint, String http, String code, MvcResult result) {
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        String line = role + " | " + endpoint + " | HTTP " + http + " | code=" + code
                + " | traceId=" + traceId + System.lineSeparator();
        try {
            Path target = Path.of("target", "qa-evidence", "m3-qa-01-permission.txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("写权限证据失败", exception);
        }
    }
}
