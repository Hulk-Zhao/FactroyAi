package com.hengce.aps.web.qa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M3-QA-01 权限 E2E 的测试装配：全量组件扫描（与生产同款）+ 真库数据源 + Flyway 迁移。
 *
 * <p>真库参数只来自 {@code APS_TEST_DB_*} 环境变量，默认库名 {@code aps_e2e}；
 * 库不存在时用维护库 {@code postgres} 自建，并执行仓库根目录的迁移链。
 * 库名门禁：{@code aps_local*} 一律拒绝，绝不建立连接。</p>
 *
 * <p>扫描时排除测试自身的 {@code @SpringBootConfiguration}/{@code @TestConfiguration}，
 * 避免把其它测试应用（例如 STOMP 最小应用）混进本上下文。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.hengce.aps",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION,
                        classes = {SpringBootConfiguration.class, TestConfiguration.class}),
                @ComponentScan.Filter(type = FilterType.ANNOTATION,
                        classes = SpringBootTest.class)
        })
public class QaE2eTestApplication {

    /**
     * 真库数据源（无连接池，测试串行执行）。
     */
    @Configuration(proxyBeanMethods = false)
    static class QaE2eDataSourceConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(QaE2eEnvironment.jdbcUrl());
            dataSource.setUsername(QaE2eEnvironment.username());
            dataSource.setPassword(QaE2eEnvironment.password());
            return dataSource;
        }
    }

    /**
     * 真库环境解析：自建库 + Flyway 迁移 + 基线库门禁。
     */
    static final class QaE2eEnvironment {

        private static final String BASELINE_PREFIX = "aps_local";

        private static final String DEFAULT_NAME = "aps_e2e";

        private static final String MAINTENANCE_DATABASE = "postgres";

        private static boolean prepared;

        private QaE2eEnvironment() {
        }

        static String databaseName() {
            String value = env("APS_TEST_DB_NAME", DEFAULT_NAME);
            requireTestDatabase(value);
            return value;
        }

        static synchronized String jdbcUrl() {
            String host = env("APS_TEST_DB_HOST", "127.0.0.1");
            String port = env("APS_TEST_DB_PORT", "55432");
            String name = databaseName();
            String url = "jdbc:postgresql://" + host + ":" + port + "/" + name;
            if (!prepared) {
                ensureDatabase(host, port, name);
                migrate(url, name);
                prepared = true;
            }
            return url;
        }

        static String username() {
            return env("APS_TEST_DB_USER", "aps");
        }

        static String password() {
            return env("APS_TEST_DB_PASSWORD", "aps_dev_pwd");
        }

        private static void requireTestDatabase(String rawName) {
            String normalized = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalStateException("测试库名不能为空");
            }
            if (normalized.startsWith(BASELINE_PREFIX)) {
                throw new IllegalStateException("拒绝连接基线库 " + rawName
                        + "：测试不得读写 aps_local / aps_local_2（项目铁律）");
            }
            if (!normalized.matches("[a-z0-9_]+")) {
                throw new IllegalStateException("测试库名只允许小写字母、数字与下划线: " + rawName);
            }
        }

        private static void ensureDatabase(String host, String port, String name) {
            String maintenanceUrl = "jdbc:postgresql://" + host + ":" + port + "/" + MAINTENANCE_DATABASE;
            try (Connection connection = DriverManager.getConnection(
                    maintenanceUrl, username(), password())) {
                if (exists(connection, name)) {
                    return;
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE " + name);
                }
            } catch (SQLException exception) {
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

        private static void migrate(String url, String name) {
            Flyway.configure()
                    .dataSource(url, username(), password())
                    .locations("filesystem:../db/migration")
                    .load()
                    .migrate();
        }

        private static String env(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }
    }
}
