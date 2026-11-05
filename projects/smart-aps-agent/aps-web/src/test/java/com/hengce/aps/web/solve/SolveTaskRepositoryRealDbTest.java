package com.hengce.aps.web.solve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M2-SE-03 / M2-SE-05 真库验收：库连接参数全部从 {@code APS_TEST_DB_*} 环境变量读取。
 *
 * <p>不依赖任何预存在的固定库名：默认库名 {@code aps_test_solve_task}，缺失时由本类
 * 通过维护库 {@code postgres} 自动创建；显式提供 {@code APS_TEST_DB_URL} 时视为调用方
 * 已准备库。服务不可用时整类跳过（{@link Assumptions}），不影响共享工作区其他模块构建。
 * 测试仅在 {@code APS_TEST_DB_ENABLED=true} 下执行。执行前由 Flyway 按
 * {@code db/migration} 把目标库迁到最新版本，与生产使用同一条迁移链；历史上手工建表、
 * 没有 {@code flyway_schema_history} 的库先 {@code clean} 再全量迁移（clean 前用
 * {@link RealDbTargetGuard} 校验数据库自报的真实库名属于受控测试库，避免误删 dev/生产库），
 * 避免测试库停留在旧结构。覆盖 V6 幂等唯一索引、条件终态写入、JSONB metrics 合并、
 * 归因 {@code task_ids} 解析与补齐。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class SolveTaskRepositoryRealDbTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SANDBOX_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000401");

    private static final String DB_HOST = env("APS_TEST_DB_HOST", "127.0.0.1");

    private static final String DB_PORT = env("APS_TEST_DB_PORT", "55432");

    private static final String DB_NAME = env("APS_TEST_DB_NAME", "aps_test_solve_task");

    private static final String DB_USER = env("APS_TEST_DB_USER", "aps");

    private static final String DB_PASSWORD = env("APS_TEST_DB_PASSWORD", "aps_dev_pwd");

    private static final String EXPLICIT_URL = env("APS_TEST_DB_URL", "");

    private static final String JDBC_URL = EXPLICIT_URL.isEmpty()
            ? "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
            : EXPLICIT_URL;

    private JdbcTemplate jdbcTemplate;

    private SolveTaskRepository repository;

    private ConstraintJustificationRepository justificationRepository;

    private final List<Long> createdTaskIds = new ArrayList<>();

    /**
     * 测试库自管 schema：缺失则先建库，再由 Flyway 迁移到最新版本。
     *
     * <p>遗留的手工建库（有业务表但无 flyway_schema_history）先 clean，再按迁移链全量重建，
     * 之后每次运行 only validate + migrate 到最新，不再依赖人工建表或迁移。</p>
     */
    @BeforeAll
    static void migrateSchemaToLatest() {
        SchemaState state;
        try {
            ensureDatabaseExists();
            state = inspectSchema();
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "真库 " + DB_NAME + " 不可用，跳过真库验收: "
                    + exception.getMessage());
            return;
        }
        Flyway flyway = Flyway.configure()
                .dataSource(JDBC_URL, DB_USER, DB_PASSWORD)
                .locations(migrationLocation())
                .cleanDisabled(false)
                .load();
        if (state.legacy()) {
            // clean 会 drop 目标库全部对象，必须用数据库自报的真实库名做白名单校验；
            // 不匹配直接抛出终止测试，绝不执行 clean。
            RealDbTargetGuard.requireCleanAllowed(state.databaseName());
            try {
                flyway.clean();
            } catch (Exception exception) {
                throw new IllegalStateException("真库 " + state.databaseName() + " clean 失败: "
                        + exception.getMessage(), exception);
            }
        }
        try {
            flyway.migrate();
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "真库 " + DB_NAME + " 迁移到最新版本失败，跳过真库验收: "
                    + exception.getMessage());
        }
    }

    /**
     * 库不存在时用维护库 {@code postgres} 自动创建，避免依赖机器上恰好存在某个固定库。
     *
     * @throws SQLException 服务不可用或建库失败
     */
    private static void ensureDatabaseExists() throws SQLException {
        if (!EXPLICIT_URL.isEmpty()) {
            return;
        }
        RealDbTargetGuard.requireCleanAllowed(DB_NAME);
        String maintenanceUrl = "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/postgres";
        try (Connection connection = DriverManager.getConnection(
                maintenanceUrl, DB_USER, DB_PASSWORD)) {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT count(*) FROM pg_database WHERE datname = ?")) {
                check.setString(1, DB_NAME);
                try (ResultSet resultSet = check.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getLong(1) > 0) {
                        return;
                    }
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE \"" + DB_NAME.replace("\"", "\"\"") + "\"");
            }
        }
    }

    /**
     * 读取环境变量，空白值退回默认值。
     *
     * @param key 变量名
     * @param defaultValue 默认值
     * @return 变量值
     */
    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String migrationLocation() {
        Path fromModule = Paths.get("..", "db", "migration");
        Path migrationDir = Files.exists(fromModule.resolve("V1__init.sql"))
                ? fromModule : Paths.get("db", "migration");
        return "filesystem:" + migrationDir.toAbsolutePath().normalize()
                .toString().replace('\\', '/');
    }

    private static SchemaState inspectSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            String databaseName = queryScalar(statement, "SELECT current_database()");
            long tables = queryCount(statement,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'");
            long flywayHistory = queryCount(statement,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'");
            return new SchemaState(tables > 0 && flywayHistory == 0, databaseName);
        }
    }

    private static String queryScalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static long queryCount(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * 目标库 schema 现状。
     *
     * @param legacy 有业务表但无 {@code flyway_schema_history}
     * @param databaseName 数据库自报的真实库名
     */
    private record SchemaState(boolean legacy, String databaseName) {
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(JDBC_URL, DB_USER, DB_PASSWORD);
        dataSource.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        try {
            template.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException exception) {
            Assumptions.assumeTrue(false, "真库 " + DB_NAME + " 不可用，跳过真库验收: "
                    + exception.getMessage());
        }
        this.jdbcTemplate = template;
        this.repository = new SolveTaskRepository(template);
        this.justificationRepository = new ConstraintJustificationRepository(template);
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate == null) {
            return;
        }
        for (Long taskId : createdTaskIds) {
            jdbcTemplate.update("DELETE FROM solve_task WHERE id = ?", taskId);
        }
        createdTaskIds.clear();
    }

    @Test
    void v6IdempotencyIndexRejectsDuplicateKey() {
        String key = "it-idem-" + UUID.randomUUID();
        long taskId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD",
                "{}", key, "PENDING");
        createdTaskIds.add(taskId);

        DuplicateKeyException duplicate = assertThrows(DuplicateKeyException.class, () ->
                repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD", "{}", key, "PENDING"));
        assertTrue(duplicate.getMessage().contains("uq_solve_task_idempotency")
                        || duplicate.getMessage().contains("uq_solve_task_sandbox_idempotency"),
                "唯一索引未生效: " + duplicate.getMessage());

        SolveTaskRepository.SolveTaskRecord existing =
                repository.findByIdempotencyKey(TENANT_ID, key).orElseThrow();
        assertEquals(taskId, existing.id());
        assertEquals("PENDING", existing.status());
    }

    @Test
    void finishIsConditionalSoCancelDoesNotOverwriteSolved() {
        long taskId = createTask("PENDING");
        assertEquals(1, repository.markRunning(TENANT_ID, taskId));
        assertEquals(1, repository.mergeMetrics(TENANT_ID, taskId, "{\"phase\":\"SOLVING\"}"));

        assertEquals(1, repository.finish(TENANT_ID, taskId, "SOLVED", 0L, -120L,
                "{\"bestScore\":\"0hard/-120soft\"}", null));
        assertEquals(0, repository.finish(TENANT_ID, taskId, "CANCELLED", null, null,
                "{\"errorCode\":\"CANCELLED\"}", "用户取消"),
                "已是终态的任务不应被取消覆盖");

        SolveTaskRepository.SolveTaskRecord record =
                repository.findById(TENANT_ID, taskId).orElseThrow();
        assertEquals("SOLVED", record.status());
        assertEquals(0L, record.scoreHard());
        assertEquals(-120L, record.scoreSoft());
        assertTrue(record.metricsJson().contains("bestScore"));
        assertTrue(record.metricsJson().contains("SOLVING"));
    }

    @Test
    void metricsMergeKeepsExistingKeys() {
        long taskId = createTask("PENDING");
        assertEquals(1, repository.mergeMetrics(TENANT_ID, taskId,
                "{\"progress\":0.4,\"phase\":\"SOLVING\"}"));
        assertEquals(1, repository.mergeMetrics(TENANT_ID, taskId, "{\"progress\":0.9}"));

        String metrics = repository.findById(TENANT_ID, taskId).orElseThrow().metricsJson();
        assertTrue(metrics.contains("SOLVING"), metrics);
        assertTrue(metrics.contains("0.9"), metrics);
    }

    @Test
    void justificationTaskIdsAreParsedAndBackfilled() {
        long taskId = createTask("SOLVED");
        UUID filledId = insertJustification(taskId,
                "[{\"type\":\"TASK\",\"id\":101},{\"type\":\"TASK\",\"taskId\":\"102\"}]");
        UUID emptyId = insertJustification(taskId, "[]");

        List<ConstraintJustificationRepository.JustificationRow> rows =
                justificationRepository.findBySolveTaskId(TENANT_ID, taskId);
        assertEquals(2, rows.size());
        List<Long> filledTaskIds = rows.stream()
                .filter(row -> row.id().equals(filledId))
                .findFirst().orElseThrow().taskIds();
        assertEquals(List.of(101L, 102L), filledTaskIds);
        assertTrue(justificationRepository.hasTaskIds(TENANT_ID, taskId));
        assertTrue(rows.stream().filter(row -> row.id().equals(emptyId))
                .findFirst().orElseThrow().taskIds().isEmpty());

        assertEquals(1, justificationRepository.backfillTaskIds(TENANT_ID, emptyId,
                List.of(103L)));
        List<Long> backfilled = justificationRepository.findBySolveTaskId(TENANT_ID, taskId)
                .stream().filter(row -> row.id().equals(emptyId))
                .findFirst().orElseThrow().taskIds();
        assertEquals(List.of(103L), backfilled);
        assertFalse(backfilled.isEmpty());
    }

    private long createTask(String status) {
        long taskId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD",
                "{}", null, status);
        createdTaskIds.add(taskId);
        return taskId;
    }

    private UUID insertJustification(long taskId, String factsJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO constraint_justification
                    (id, tenant_id, solve_task_id, constraint_id, constraint_name,
                     score_impact, implicated_facts, explanation_zh)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                id, TENANT_ID, taskId, "C-RESOURCE-NO-OVERLAP", "资源不重叠",
                "HARD", factsJson, "真库验收种子");
        return id;
    }
}
