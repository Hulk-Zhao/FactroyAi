package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.UserMapper;
import com.hengce.aps.service.audit.AuditLogService;
import com.hengce.aps.service.rbac.AdminPasswordBootstrap;
import com.hengce.aps.service.rbac.AdminPasswordBootstrap.Outcome;
import com.hengce.aps.service.rbac.AuthService;
import com.hengce.aps.service.rbac.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * admin 首次口令初始化的真库测试：四条门控分支、写入后可直接登录、已有口令永不覆盖。
 *
 * <p>直接对种子 {@code admin} 操作，但每个用例前后都还原原 {@code password_hash} 与状态，
 * 避免污染目标库；推荐仅在自建临时库上执行。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class AdminPasswordBootstrapRealDbTest extends AbstractRealDbIntegrationTest {

    private static final String INITIAL_PASSWORD = "Init#Pass-2026";

    private static final String OTHER_PASSWORD = "Other#Pass-2027";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuthService authService;

    private String originalHash;

    @BeforeEach
    void resetAdminPassword() {
        originalHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE username = 'admin' AND deleted = false "
                        + "AND tenant_id = ?", String.class, TenantContext.DEFAULT_TENANT_ID);
        jdbc.update("UPDATE users SET password_hash = NULL, status = 'ACTIVE', locked_until = NULL, "
                + "ext_attrs = '{}'::jsonb WHERE username = 'admin' AND tenant_id = ?",
                TenantContext.DEFAULT_TENANT_ID);
    }

    @AfterEach
    void restoreAdminPassword() {
        jdbc.update("UPDATE users SET password_hash = ?, status = 'ACTIVE', locked_until = NULL, "
                + "ext_attrs = '{}'::jsonb WHERE username = 'admin' AND tenant_id = ?",
                originalHash, TenantContext.DEFAULT_TENANT_ID);
    }

    @Test
    void devProfileWithEnvAndNullHashWritesPasswordAndLoginSucceeds() {
        long auditBefore = count("SELECT count(*) FROM audit_log WHERE action = 'USER_UPDATED' "
                + "AND resource_type = 'USER' AND resource_id = 'admin'");
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            Outcome outcome = bootstrap(devEnvironment(INITIAL_PASSWORD)).bootstrap();

            assertEquals(Outcome.WRITTEN, outcome);
            String stored = adminPasswordHash();
            assertTrue(stored != null && stored.startsWith("$2"), "应为 bcrypt 哈希: " + stored);
            assertTrue(passwordEncoder.matches(INITIAL_PASSWORD, stored));

            AuthenticatedUser user = authService.login("admin", INITIAL_PASSWORD);
            assertEquals(adminId(), user.userId());
            assertTrue(user.roleCodes().contains("ADMIN"), "种子 admin 应带 ADMIN 角色: " + user.roleCodes());

            assertEquals(auditBefore + 1, count("SELECT count(*) FROM audit_log WHERE action = 'USER_UPDATED' "
                    + "AND resource_type = 'USER' AND resource_id = 'admin'"), "应落一条 USER_UPDATED 审计");
            assertFalse(logContains(appender, INITIAL_PASSWORD), "日志不得出现口令明文");
            evidence("bootstrap -> " + outcome + "，bcrypt 前缀=" + stored.substring(0, 7)
                    + "，AuthService 登录成功 roles=" + user.roleCodes());
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void existingPasswordIsNeverOverwritten() {
        assertEquals(Outcome.WRITTEN, bootstrap(devEnvironment(INITIAL_PASSWORD)).bootstrap());
        String firstHash = adminPasswordHash();

        Outcome second = bootstrap(devEnvironment(OTHER_PASSWORD)).bootstrap();

        assertEquals(Outcome.ALREADY_SET, second);
        assertEquals(firstHash, adminPasswordHash(), "已有口令必须保持不变");
        assertTrue(passwordEncoder.matches(INITIAL_PASSWORD, adminPasswordHash()));
        assertFalse(passwordEncoder.matches(OTHER_PASSWORD, adminPasswordHash()));
        assertEquals(adminId(), authService.login("admin", INITIAL_PASSWORD).userId(),
                "原口令仍可登录");
        BizException rejected = assertThrows(BizException.class,
                () -> authService.login("admin", OTHER_PASSWORD));
        assertEquals(ErrorCode.UNAUTHORIZED, rejected.getErrorCode());
        evidence("第二次 bootstrap -> " + second + "，password_hash 未变，原口令仍可登录");
    }

    @Test
    void missingEnvIsNoOpWithoutException() {
        assertEquals(Outcome.SKIPPED_NO_PASSWORD, bootstrap(devEnvironment(null)).bootstrap());
        assertNull(adminPasswordHash(), "缺少环境变量时不得写入");

        MockEnvironment blank = devEnvironment("   ");
        assertEquals(Outcome.SKIPPED_NO_PASSWORD, bootstrap(blank).bootstrap());
        assertNull(adminPasswordHash(), "空白环境变量时不得写入");
        evidence("dev + 无 APS_ADMIN_INIT_PASSWORD -> no-op，password_hash 仍为 NULL");
    }

    @Test
    void nonDevProfileIsNoOpAndLogsReason() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            prod.setProperty(AdminPasswordBootstrap.PASSWORD_ENV_KEY, INITIAL_PASSWORD);

            assertEquals(Outcome.SKIPPED_NOT_DEV, bootstrap(prod).bootstrap());

            assertNull(adminPasswordHash(), "非 dev profile 不得写入");
            assertTrue(logContains(appender, "未激活 dev profile"), "日志应说明跳过原因");
            assertFalse(logContains(appender, INITIAL_PASSWORD), "日志不得出现口令明文");
            evidence("prod profile + 环境变量 -> no-op，日志=" + messages(appender));
        } finally {
            detachAppender(appender);
        }
    }

    private AdminPasswordBootstrap bootstrap(Environment environment) {
        return new AdminPasswordBootstrap(userMapper, passwordEncoder, auditLogService, environment,
                java.time.Clock.systemUTC());
    }

    private static MockEnvironment devEnvironment(String password) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(AdminPasswordBootstrap.DEV_PROFILE);
        if (password != null) {
            environment.setProperty(AdminPasswordBootstrap.PASSWORD_ENV_KEY, password);
        }
        return environment;
    }

    private String adminPasswordHash() {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'admin' "
                + "AND deleted = false AND tenant_id = ?", String.class, TenantContext.DEFAULT_TENANT_ID);
    }

    private java.util.UUID adminId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin' AND deleted = false "
                + "AND tenant_id = ?", java.util.UUID.class, TenantContext.DEFAULT_TENANT_ID);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminPasswordBootstrap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminPasswordBootstrap.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static boolean logContains(ListAppender<ILoggingEvent> appender, String keyword) {
        return appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains(keyword));
    }

    private static String messages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce((left, right) -> left + " | " + right).orElse("");
    }
}
