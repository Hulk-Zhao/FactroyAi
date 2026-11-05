package com.hengce.aps.agent.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;

/**
 * Agent 真库集成测试连接参数，全部来自环境变量，不落任何配置文件。
 *
 * <p>启用开关 {@code APS_TEST_DB_ENABLED=true}；默认库名 {@code aps_test_shared}，
 * 由本类按需自建并执行 Flyway 迁移到 V13。与 aps-service 的测试基座遵循同一门禁：
 * 库名属于基线库（{@code aps_local} 及其变体）时立即失败，绝不连接。</p>
 */
final class AgentDbEnvironment {

    private static final String ENABLED = "APS_TEST_DB_ENABLED";

    private static final String URL = "APS_TEST_DB_URL";

    private static final String HOST = "APS_TEST_DB_HOST";

    private static final String PORT = "APS_TEST_DB_PORT";

    private static final String NAME = "APS_TEST_DB_NAME";

    private static final String USER = "APS_TEST_DB_USER";

    private static final String PASSWORD = "APS_TEST_DB_PASSWORD";

    private static final String NAME_SYSTEM_PROPERTY = "aps.test.db.name";

    private static final String DEFAULT_HOST = "127.0.0.1";

    private static final String DEFAULT_PORT = "55432";

    private static final String DEFAULT_NAME = "aps_test_shared";

    private static final String DEFAULT_USER = "aps";

    private static final String DEFAULT_PASSWORD = "aps_dev_pwd";

    private static final String BASELINE_PREFIX = "aps_local";

    private static final String MAINTENANCE_DATABASE = "postgres";

    private static final String MIGRATION_LOCATION = "filesystem:../db/migration";

    private static final Set<String> PREPARED = ConcurrentHashMap.newKeySet();

    private static final Set<String> MIGRATED = ConcurrentHashMap.newKeySet();

    private AgentDbEnvironment() {
    }

    static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv(ENABLED));
    }

    static String databaseName() {
        String property = System.getProperty(NAME_SYSTEM_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        return value(NAME, DEFAULT_NAME);
    }

    static String jdbcUrl() {
        String explicit = System.getenv(URL);
        if (explicit != null && !explicit.isBlank()) {
            String url = explicit.trim();
            String name = databaseFromUrl(url);
            requireTestDatabase(name);
            migrateToLatest(url, name);
            return url;
        }
        String host = value(HOST, DEFAULT_HOST);
        String port = value(PORT, DEFAULT_PORT);
        String name = databaseName();
        requireTestDatabase(name);
        ensureDatabase(host, port, name);
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + name;
        migrateToLatest(url, name);
        return url;
    }

    static String username() {
        return value(USER, DEFAULT_USER);
    }

    static String password() {
        return value(PASSWORD, DEFAULT_PASSWORD);
    }

    static void requireTestDatabase(String databaseName) {
        String normalized = databaseName == null ? "" : databaseName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("测试库名不能为空");
        }
        if (normalized.startsWith(BASELINE_PREFIX)) {
            throw new IllegalStateException("拒绝连接基线库 " + databaseName + "：测试不得读写 aps_local");
        }
    }

    private static void ensureDatabase(String host, String port, String name) {
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("测试库名只允许字母、数字与下划线: " + name);
        }
        String key = host + ":" + port + "/" + name;
        if (!PREPARED.add(key)) {
            return;
        }
        String maintenanceUrl = "jdbc:postgresql://" + host + ":" + port + "/" + MAINTENANCE_DATABASE;
        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username(), password())) {
            if (!exists(connection, name)) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE DATABASE " + name);
                }
            }
        } catch (SQLException exception) {
            PREPARED.remove(key);
            throw new IllegalStateException("无法自建测试库 " + name + "：" + exception.getMessage(), exception);
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

    private static void migrateToLatest(String url, String name) {
        if (!MIGRATED.add(name)) {
            return;
        }
        try {
            Flyway.configure()
                    .dataSource(url, username(), password())
                    .locations(MIGRATION_LOCATION)
                    .load()
                    .migrate();
        } catch (RuntimeException exception) {
            MIGRATED.remove(name);
            throw new IllegalStateException("测试库 " + name + " 执行 Flyway 迁移失败："
                    + exception.getMessage(), exception);
        }
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

    private static String value(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
