package com.hengce.aps.service.it;

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
 * 真库集成测试连接参数，全部从环境变量读取，不落任何会被提交的配置文件。
 *
 * <p>启用开关：{@code APS_TEST_DB_ENABLED=true}。未启用时所有真库测试由 JUnit 条件跳过，
 * 保证无数据库的 CI/开发环境仍可正常执行单测。</p>
 *
 * <p>连接参数优先使用 {@code APS_TEST_DB_URL}；未提供时按 host/port/name/user/password
 * 拼装 JDBC URL。默认库名是测试自有库 {@code aps_test_shared}，由本类按需自建
 * （维护库 {@code postgres}）并执行 Flyway 迁移到最新版本，不依赖任何预存在的固定库名。</p>
 *
 * <p><b>硬门禁</b>：解析出的库名属于基线库（{@code aps_local} 及其变体，
 * 如 {@code aps_local_2}）时立即抛出异常，绝不建立连接。测试不得读写基线库是项目铁律，
 * 与是否启用真库测试、是否显式传入库名无关。</p>
 */
final class RealDbEnvironment {

    private static final String ENABLED = "APS_TEST_DB_ENABLED";

    private static final String URL = "APS_TEST_DB_URL";

    private static final String HOST = "APS_TEST_DB_HOST";

    private static final String PORT = "APS_TEST_DB_PORT";

    private static final String NAME = "APS_TEST_DB_NAME";

    private static final String USER = "APS_TEST_DB_USER";

    private static final String PASSWORD = "APS_TEST_DB_PASSWORD";

    /**
     * 测试可在本进程内用系统属性覆盖库名（环境变量无法在 JUnit 运行时注入），
     * 优先级高于 {@code APS_TEST_DB_NAME}。
     */
    private static final String NAME_SYSTEM_PROPERTY = "aps.test.db.name";

    private static final String DEFAULT_HOST = "127.0.0.1";

    private static final String DEFAULT_PORT = "55432";

    private static final String DEFAULT_NAME = "aps_test_shared";

    private static final String DEFAULT_USER = "aps";

    private static final String DEFAULT_PASSWORD = "aps_dev_pwd";

    private static final String BASELINE_PREFIX = "aps_local";

    private static final String MAINTENANCE_DATABASE = "postgres";

    private static final String MIGRATION_LOCATION = "filesystem:../db/migration";

    /**
     * 已确认存在（或已创建）的库，避免每张用例重复连接维护库。
     */
    private static final Set<String> PREPARED = ConcurrentHashMap.newKeySet();

    /**
     * 已执行迁移的库，避免每次调用 {@link #jdbcUrl()} 都重复扫描迁移脚本。
     */
    private static final Set<String> MIGRATED = ConcurrentHashMap.newKeySet();

    private RealDbEnvironment() {
    }

    static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv(ENABLED));
    }

    /**
     * 解析目标库名：系统属性覆盖 → 环境变量 → 测试自有默认库。
     *
     * @return 目标库名
     */
    static String databaseName() {
        String property = System.getProperty(NAME_SYSTEM_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        return value(NAME, DEFAULT_NAME);
    }

    /**
     * 构造（并按需自建 + Flyway 迁移）测试库 JDBC URL；解析到基线库时立即失败，不建立连接。
     *
     * @return 目标库 JDBC URL
     */
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

    /**
     * 库名门禁：指向基线库或为空时立即抛出 {@link IllegalStateException}，调用方不得继续连接。
     *
     * @param databaseName 待校验库名
     */
    static void requireTestDatabase(String databaseName) {
        String normalized = databaseName == null ? "" : databaseName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("测试库名不能为空：真库测试必须指向受控测试库，不能使用空库名");
        }
        if (normalized.startsWith(BASELINE_PREFIX)) {
            throw new IllegalStateException("拒绝连接基线库 " + databaseName
                    + "：测试不得读写 aps_local / aps_local_2（项目铁律）；"
                    + "请改用测试自有库（默认 " + DEFAULT_NAME + "）或显式设置 " + NAME);
        }
    }

    /**
     * 按需自建测试库：已存在则跳过，不存在则在维护库上创建。
     */
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

    /**
     * 执行 Flyway 迁移（每个库在单个 JVM 内只执行一次），保证测试自有库具备与生产一致的 schema。
     */
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

    /**
     * 从 JDBC URL 提取库名（不含查询串）；无路径时返回空串交由门禁拒绝。
     */
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
