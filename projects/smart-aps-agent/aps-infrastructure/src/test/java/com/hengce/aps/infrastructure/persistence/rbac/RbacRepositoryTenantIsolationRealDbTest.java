package com.hengce.aps.infrastructure.persistence.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link RbacRepository} 租户谓词真库测试（安全扫描 P2-2）。
 *
 * <p>{@code user_role} / {@code role_permission} 没有 {@code tenant_id} 列，跨租户防护依赖
 * {@code users.tenant_id} / {@code role.tenant_id} 守卫与 DELETE 的 EXISTS 谓词。本类断言：
 * 在租户 A 上下文中替换租户 B 的用户角色或角色权限必须被拒绝且对方数据保持不变；
 * 本租户内的替换仍必须生效。</p>
 *
 * <p>不装载 Spring 上下文；按 {@code APS_TEST_DB_*} 参数直连测试自有库，
 * 账号库不可写时由本类按需自建并执行 Flyway 迁移。数据在用例后按主键硬删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class RbacRepositoryTenantIsolationRealDbTest {

    /**
     * V1 种子租户（本租户）。
     */
    private static final UUID TENANT_A = TenantContext.DEFAULT_TENANT_ID;

    /**
     * 用例创建的第二个租户（对方租户）。
     */
    private static final UUID TENANT_B = UUID.randomUUID();

    private static final String NAME_ENV = "APS_TEST_DB_NAME";

    private static final String URL_ENV = "APS_TEST_DB_URL";

    private static final String DEFAULT_NAME = "aps_test_shared";

    private static final String MAINTENANCE_DATABASE = "postgres";

    private static final Map<String, Boolean> PREPARED = new ConcurrentHashMap<>();

    private JdbcTemplate jdbc;

    private RbacRepository repository;

    private UUID userA;

    private UUID userB;

    private UUID roleA;

    private UUID roleB;

    private UUID permissionA;

    private UUID permissionB;

    @BeforeAll
    static void migrateToLatest() {
        if (!enabled()) {
            return;
        }
        ensureDatabase();
        Flyway.configure()
                .dataSource(jdbcUrl(), username(), password())
                .locations("filesystem:../db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl());
        dataSource.setUsername(username());
        dataSource.setPassword(password());
        jdbc = new JdbcTemplate(dataSource);
        repository = new RbacRepository(jdbc);

        jdbc.update("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)",
                TENANT_B, "QA-RBAC-" + TENANT_B.toString().substring(0, 8), "QA RBAC 跨租户校验");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userA = insertUser(TENANT_A, "QA-RBAC-A-" + suffix);
        userB = insertUser(TENANT_B, "QA-RBAC-B-" + suffix);
        roleA = insertRole(TENANT_A, "QA_ROLE_A_" + suffix);
        roleB = insertRole(TENANT_B, "QA_ROLE_B_" + suffix);
        permissionA = insertPermission(TENANT_A, "QA_PERM_A_" + suffix);
        permissionB = insertPermission(TENANT_B, "QA_PERM_B_" + suffix);

        jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userB, roleB);
        jdbc.update("INSERT INTO role_permission (role_id, permission_id) VALUES (?, ?)", roleB, permissionB);

        TenantContext.setTenantId(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?)", userA, userB);
        jdbc.update("DELETE FROM role_permission WHERE role_id IN (?, ?)", roleA, roleB);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userA, userB);
        jdbc.update("DELETE FROM role WHERE id IN (?, ?)", roleA, roleB);
        jdbc.update("DELETE FROM permission WHERE id IN (?, ?)", permissionA, permissionB);
        jdbc.update("DELETE FROM tenant WHERE id = ?", TENANT_B);
    }

    @Test
    void crossTenantReplaceIsRejectedAndLeavesOtherTenantRowsUntouched() {
        IllegalStateException userRoles = assertThrows(IllegalStateException.class,
                () -> repository.replaceUserRoles(TENANT_A, userB, List.of(roleA)));
        assertEquals("跨租户用户角色替换被拒绝", userRoles.getMessage());

        IllegalStateException rolePermissions = assertThrows(IllegalStateException.class,
                () -> repository.replaceRolePermissions(TENANT_A, roleB, List.of(permissionA)));
        assertEquals("跨租户角色操作被拒绝", rolePermissions.getMessage());

        assertEquals(1, count("SELECT count(*) FROM user_role WHERE user_id = ? AND role_id = ?", userB, roleB),
                "对方租户用户角色关联不得被删除");
        assertEquals(1, count("SELECT count(*) FROM role_permission WHERE role_id = ? AND permission_id = ?",
                roleB, permissionB), "对方租户角色权限关联不得被删除");
        assertTrue(repository.findRoleCodesByUserId(TENANT_A, userB).isEmpty(),
                "本租户上下文查不到对方租户用户的角色");
        assertTrue(repository.findPermissionCodesByRoleId(TENANT_A, roleB).isEmpty(),
                "本租户上下文查不到对方租户角色的权限");
    }

    @Test
    void ownTenantReplaceTakesEffect() {
        repository.replaceUserRoles(TENANT_A, userA, List.of(roleA));
        assertEquals(1, count("SELECT count(*) FROM user_role WHERE user_id = ? AND role_id = ?", userA, roleA),
                "本租户用户角色替换必须生效");

        repository.replaceRolePermissions(TENANT_A, roleA, List.of(permissionA));
        assertEquals(1, count("SELECT count(*) FROM role_permission WHERE role_id = ? AND permission_id = ?",
                roleA, permissionA), "本租户角色权限替换必须生效");
        assertEquals(1, repository.findPermissionCodesByRoleId(TENANT_A, roleA).size(),
                "本租户可读到自己的角色权限");
    }

    private UUID insertUser(UUID tenantId, String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, username, status) VALUES (?, ?, ?, 'ACTIVE')",
                id, tenantId, username);
        return id;
    }

    private UUID insertRole(UUID tenantId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO role (id, tenant_id, code, name) VALUES (?, ?, ?, ?)",
                id, tenantId, code, "QA RBAC 角色");
        return id;
    }

    private UUID insertPermission(UUID tenantId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO permission (id, tenant_id, code, name, module) VALUES (?, ?, ?, ?, 'QA')",
                id, tenantId, code, "QA RBAC 权限");
        return id;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("APS_TEST_DB_ENABLED"));
    }

    private static String jdbcUrl() {
        String explicit = System.getenv(URL_ENV);
        if (explicit != null && !explicit.isBlank()) {
            requireTestDatabase(databaseFromUrl(explicit.trim()));
            return explicit.trim();
        }
        String name = databaseName();
        requireTestDatabase(name);
        return "jdbc:postgresql://" + host() + ":" + port() + "/" + name;
    }

    private static String databaseName() {
        String name = env(NAME_ENV, DEFAULT_NAME);
        if (name.isBlank()) {
            throw new IllegalStateException("测试库名不能为空");
        }
        return name;
    }

    private static String databaseFromUrl(String url) {
        int schemeEnd = url.indexOf("//");
        int pathStart = schemeEnd < 0 ? -1 : url.indexOf('/', schemeEnd + 2);
        if (pathStart < 0) {
            return "";
        }
        String path = url.substring(pathStart + 1);
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /**
     * 库名硬门禁：任何变体都不得指向基线库。
     */
    private static void requireTestDatabase(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("aps_local")) {
            throw new IllegalStateException("拒绝连接基线库 " + name
                    + "：测试不得读写 aps_local / aps_local_2（项目铁律）");
        }
    }

    /**
     * 按需自建测试库（未显式提供 URL 时）。
     */
    private static void ensureDatabase() {
        if (System.getenv(URL_ENV) != null && !System.getenv(URL_ENV).isBlank()) {
            return;
        }
        String name = databaseName();
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("测试库名只允许字母、数字与下划线: " + name);
        }
        String key = host() + ":" + port() + "/" + name;
        if (PREPARED.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        String maintenanceUrl = "jdbc:postgresql://" + host() + ":" + port() + "/" + MAINTENANCE_DATABASE;
        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username(), password())) {
            if (!exists(connection, name)) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE DATABASE " + name);
                }
            }
        } catch (SQLException exception) {
            PREPARED.remove(key);
            throw new IllegalStateException("无法自建测试库 " + name + "（" + maintenanceUrl + "）："
                    + exception.getMessage(), exception);
        }
    }

    private static boolean exists(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String host() {
        return env("APS_TEST_DB_HOST", "127.0.0.1");
    }

    private static String port() {
        return env("APS_TEST_DB_PORT", "55432");
    }

    private static String username() {
        return env("APS_TEST_DB_USER", "aps");
    }

    private static String password() {
        return env("APS_TEST_DB_PASSWORD", "aps_dev_pwd");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
