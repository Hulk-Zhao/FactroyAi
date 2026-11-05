package com.hengce.aps.infrastructure.persistence.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * {@link ScheduleRepository} 租户谓词真库测试（安全扫描 P2-2）。
 *
 * <p>覆盖 {@code finishSolveTask} 与 {@code deleteJustificationsByTaskIds} 两处
 * 按主键直写/直删的 SQL：跨租户的求解任务主键、归因事实主键在本租户上下文下
 * 必须读不到、改不动、删不掉；本租户的正常读写仍必须生效。</p>
 *
 * <p>不装载 Spring 上下文；按 {@code APS_TEST_DB_*} 参数直连测试自有库，
 * 账号库不可写时由本类按需自建并执行 Flyway 迁移。数据在用例后按主键硬删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class ScheduleRepositoryTenantIsolationRealDbTest {

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

    private static final long FACT_B = 990001L;

    private static final OffsetDateTime WIDE_HORIZON_START =
            OffsetDateTime.parse("2000-01-01T00:00:00Z");

    private static final OffsetDateTime WIDE_HORIZON_END =
            OffsetDateTime.parse("2100-01-01T00:00:00Z");

    private static final Map<String, Boolean> PREPARED = new ConcurrentHashMap<>();

    private JdbcTemplate jdbc;

    private ScheduleRepository repository;

    private UUID sandboxB;

    private long solveTaskB;

    private long taskB;

    private UUID materialB;

    private UUID orderB;

    private UUID justificationB;

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
        repository = new ScheduleRepository(jdbc);

        jdbc.update("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)",
                TENANT_B, "QA-TENANT-" + TENANT_B.toString().substring(0, 8), "QA 跨租户校验");

        sandboxB = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot) "
                        + "VALUES (?, ?, ?, 'WHAT_IF', '{}'::jsonb)",
                sandboxB, TENANT_B, "QA-TENANT-SBX-" + sandboxB);

        materialB = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit) "
                        + "VALUES (?, ?, ?, ?, 'RAW', 'PCS')",
                materialB, TENANT_B, "QA-TENANT-MAT-" + materialB, "QA 跨租户物料");

        orderB = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderB, TENANT_B, "QA-TENANT-ORD-" + orderB, materialB, BigDecimal.TEN,
                LocalDate.of(2030, 2, 1));

        solveTaskB = insertSolveTask();
        taskB = insertTask();
        justificationB = insertJustification();

        TenantContext.setTenantId(TENANT_A);
    }

    @AfterEach
    void tearDown() {
        if (jdbc == null) {
            TenantContext.clear();
            return;
        }
        TenantContext.clear();
        jdbc.update("DELETE FROM constraint_justification WHERE tenant_id = ?", TENANT_B);
        jdbc.update("DELETE FROM solve_task WHERE id = ?", solveTaskB);
        jdbc.update("DELETE FROM task WHERE id = ?", taskB);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderB);
        jdbc.update("DELETE FROM material WHERE id = ?", materialB);
        jdbc.update("DELETE FROM sandbox WHERE id = ?", sandboxB);
        jdbc.update("DELETE FROM tenant WHERE id = ?", TENANT_B);
    }

    @Test
    void crossTenantShouldNotReadUpdateOrDeleteOtherTenantRows() {
        assertTrue(repository.findScheduledTaskRows(TENANT_A, sandboxB).isEmpty(),
                "本租户上下文查不到对方租户场景下的任务");
        assertTrue(repository.findFrozenTasks(TENANT_A, sandboxB,
                        WIDE_HORIZON_START, WIDE_HORIZON_END).isEmpty(),
                "本租户上下文查不到对方租户的冻结任务");

        repository.finishSolveTask(solveTaskB, "SOLVED", 0L, 0L, "{\"phase\":\"DONE\"}", "跨租户尝试");
        assertEquals("PENDING", solveTaskStatus(solveTaskB), "跨租户不得更新对方求解任务");

        repository.deleteJustificationsByTaskIds(List.of(FACT_B));
        assertEquals(1, justificationCount(TENANT_B), "跨租户不得删除对方归因");
    }

    @Test
    void ownTenantShouldReadUpdateAndDeleteOwnRows() {
        TenantContext.setTenantId(TENANT_B);

        assertEquals(1, repository.findScheduledTaskRows(TENANT_B, sandboxB).size(),
                "本租户可读取自己的任务");
        assertEquals(1, repository.findFrozenTasks(TENANT_B, sandboxB,
                        WIDE_HORIZON_START, WIDE_HORIZON_END).size(),
                "本租户可读取自己的冻结任务");

        repository.finishSolveTask(solveTaskB, "SOLVED", 0L, 0L, "{\"phase\":\"DONE\"}", "本租户更新");
        assertEquals("SOLVED", solveTaskStatus(solveTaskB), "本租户更新必须生效");

        repository.deleteJustificationsByTaskIds(List.of(FACT_B));
        assertEquals(0, justificationCount(TENANT_B), "本租户删除自己的归因必须生效");
        assertFalse(jdbc.queryForList("SELECT id FROM constraint_justification WHERE id = ?",
                UUID.class, justificationB).stream().findFirst().isPresent(),
                "归因行必须已被删除");
    }

    private long insertSolveTask() {
        Long id = jdbc.queryForObject(
                "INSERT INTO solve_task (tenant_id, sandbox_id, engine, status, config) "
                        + "VALUES (?, ?, 'TIMEFOLD', 'PENDING', '{}'::jsonb) RETURNING id",
                Long.class, TENANT_B, sandboxB);
        assertTrue(id != null, "solve_task 必须插入成功");
        return id;
    }

    private long insertTask() {
        Long id = jdbc.queryForObject(
                "INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, status, "
                        + "start_time, end_time) "
                        + "VALUES (?, ?, ?, 1, 'FROZEN', ?, ?) RETURNING id",
                Long.class, TENANT_B, orderB, sandboxB,
                java.time.OffsetDateTime.parse("2030-01-01T00:00:00Z"),
                java.time.OffsetDateTime.parse("2030-01-01T04:00:00Z"));
        assertTrue(id != null, "task 必须插入成功");
        return id;
    }

    private UUID insertJustification() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO constraint_justification (id, tenant_id, solve_task_id, constraint_id, "
                        + "implicated_facts) VALUES (?, ?, ?, 'QA-TENANT-CONSTRAINT', "
                        + "?::jsonb)",
                id, TENANT_B, solveTaskB, "[{\"type\":\"TASK\",\"id\":" + FACT_B + "}]");
        return id;
    }

    private String solveTaskStatus(long solveTaskId) {
        return jdbc.queryForObject("SELECT status FROM solve_task WHERE id = ?", String.class, solveTaskId);
    }

    private int justificationCount(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM constraint_justification WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
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
