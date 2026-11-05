package com.hengce.aps.infrastructure.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.config.ApsTenantFilterProperties;
import com.hengce.aps.infrastructure.persistence.config.MybatisPlusConfiguration;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import com.hengce.aps.infrastructure.persistence.process.template.handler.UuidTypeHandler;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M3-BE-04 多租户过滤开关真库测试。
 *
 * <p>直接构造 MyBatis-Plus {@link SqlSessionFactory}（不装载 Spring 上下文），用
 * {@link MaterialMapper} 验证开关两态：</p>
 * <ul>
 *   <li>开启（默认）：租户 B 上下文读不到租户 A 的同编码物料，且实际下发 SQL 含 tenant_id 谓词；</li>
 *   <li>关闭：同一 handler/工厂切换开关后，谓词消失，两个租户的同编码物料都可见；</li>
 *   <li>关闭后单租户数据仍然正确（租户 A 只看到自己的行）。</li>
 * </ul>
 *
 * <p>开关切换在同一个 {@link ApsTenantFilterProperties} 实例上完成，证明 handler 读取的是
 * 内存配置而非每次请求的环境变量。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class MaterialMapperTenantFilterRealDbTest {

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

    private static final java.util.regex.Pattern TENANT_PREDICATE =
            java.util.regex.Pattern.compile("tenant_id\\s*=", java.util.regex.Pattern.CASE_INSENSITIVE);

    private JdbcTemplate jdbc;

    private UUID materialA;

    private UUID materialB;

    private String code;

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

        jdbc.update("INSERT INTO tenant (id, code, name) VALUES (?, ?, ?)",
                TENANT_B, "QA-TENANT-" + TENANT_B.toString().substring(0, 8), "QA 租户过滤开关校验");

        code = "QA-TENANT-FILTER-" + UUID.randomUUID().toString().substring(0, 8);
        materialA = insertMaterial(TENANT_A, code);
        materialB = insertMaterial(TENANT_B, code);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM material WHERE id IN (?, ?)", materialA, materialB);
        jdbc.update("DELETE FROM tenant WHERE id = ?", TENANT_B);
    }

    @Test
    void enabledFilterHidesOtherTenantRowsAndInjectsPredicate() {
        ApsTenantFilterProperties properties = new ApsTenantFilterProperties();
        SqlCaptureInterceptor capture = new SqlCaptureInterceptor();
        SqlSessionFactory factory = factory(properties, capture);

        TenantContext.setTenantId(TENANT_B);
        try (SqlSession session = factory.openSession()) {
            MaterialMapper mapper = session.getMapper(MaterialMapper.class);

            List<Material> rows = mapper.selectList(new QueryWrapper<Material>().eq("code", code));
            assertEquals(1, rows.size(), "租户 B 只能读到自己的一条物料");
            assertEquals(TENANT_B, rows.get(0).getTenantId());

            assertNull(mapper.selectById(materialA), "租户 B 不得按主键读到租户 A 的物料");
        }

        assertTrue(capture.containsTenantPredicate(), "开启态实际 SQL 必须含 tenant_id 谓词：" + capture.sqls());
    }

    @Test
    void disabledFilterRemovesPredicateButSingleTenantDataStaysCorrect() {
        ApsTenantFilterProperties properties = new ApsTenantFilterProperties();
        SqlCaptureInterceptor capture = new SqlCaptureInterceptor();
        SqlSessionFactory factory = factory(properties, capture);

        TenantContext.setTenantId(TENANT_B);
        try (SqlSession session = factory.openSession()) {
            MaterialMapper mapper = session.getMapper(MaterialMapper.class);
            List<Material> rows = mapper.selectList(new QueryWrapper<Material>().eq("code", code));
            assertEquals(1, rows.size(), "开启态先确认隔离生效");
        }
        assertTrue(capture.containsTenantPredicate());

        properties.setEnabled(false);
        capture.clear();
        try (SqlSession session = factory.openSession()) {
            MaterialMapper mapper = session.getMapper(MaterialMapper.class);

            List<Material> rows = mapper.selectList(new QueryWrapper<Material>().eq("code", code));
            assertEquals(2, rows.size(), "关闭态不再加谓词，两个租户的同编码物料都可见");
        }
        assertTrue(capture.hasSql(), "关闭态必须确实执行了查询");
        assertTrue(capture.noneContainsTenantPredicate(), "关闭态实际 SQL 不得残留 tenant_id 谓词：" + capture.sqls());

        capture.clear();
        TenantContext.setTenantId(TENANT_A);
        try (SqlSession session = factory.openSession()) {
            MaterialMapper mapper = session.getMapper(MaterialMapper.class);
            Material own = mapper.selectById(materialA);
            assertEquals(TENANT_A, own.getTenantId(), "关闭态下按主键读取本租户数据仍然正确");
        }
    }

    @Test
    void toggleOnLivePropertiesIsReadByHandlerWithoutEnvReread() {
        ApsTenantFilterProperties properties = new ApsTenantFilterProperties();
        SqlCaptureInterceptor capture = new SqlCaptureInterceptor();
        SqlSessionFactory factory = factory(properties, capture);
        TenantContext.setTenantId(TENANT_B);

        try (SqlSession session = factory.openSession()) {
            MaterialMapper mapper = session.getMapper(MaterialMapper.class);
            assertEquals(1, mapper.selectList(new QueryWrapper<Material>().eq("code", code)).size());
        }
        assertTrue(capture.containsTenantPredicate());

        properties.setEnabled(false);
        capture.clear();
        try (SqlSession session = factory.openSession()) {
            MaterialMapper mapper = session.getMapper(MaterialMapper.class);
            assertEquals(2, mapper.selectList(new QueryWrapper<Material>().eq("code", code)).size());
        }
        assertTrue(capture.noneContainsTenantPredicate(), "同一 handler 切换开关后谓词应立即消失");
    }

    private UUID insertMaterial(UUID tenantId, String materialCode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit) VALUES (?, ?, ?, ?, 'RAW', 'PCS')",
                id, tenantId, materialCode, "QA 租户过滤物料");
        return id;
    }

    private SqlSessionFactory factory(ApsTenantFilterProperties properties, SqlCaptureInterceptor capture) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, new UuidTypeHandler());
        configuration.getTypeHandlerRegistry().register(Serializable.class, ObjectTypeHandler.class);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl(), username(), password());
        dataSource.setDriverClassName("org.postgresql.Driver");
        configuration.setEnvironment(new Environment("tenant-filter-test", new JdbcTransactionFactory(), dataSource));
        configuration.addInterceptor(capture);
        configuration.addInterceptor(new MybatisPlusConfiguration(properties).mybatisPlusInterceptor());
        configuration.addMapper(MaterialMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 捕获最终下发到 JDBC 的 SQL（已包含租户插件改写结果）。
     */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static final class SqlCaptureInterceptor implements Interceptor {

        private final List<String> sqls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            sqls.add(handler.getBoundSql().getSql().replaceAll("\\s+", " ").trim());
            return invocation.proceed();
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
            // 测试拦截器无需额外属性
        }

        void clear() {
            sqls.clear();
        }

        boolean hasSql() {
            return !sqls.isEmpty();
        }

        boolean containsTenantPredicate() {
            return sqls.stream().anyMatch(MaterialMapperTenantFilterRealDbTest::hasTenantPredicate);
        }

        boolean noneContainsTenantPredicate() {
            return sqls.stream().noneMatch(MaterialMapperTenantFilterRealDbTest::hasTenantPredicate);
        }

        List<String> sqls() {
            return new ArrayList<>(sqls);
        }
    }

    /**
     * 只认 WHERE 中的 {@code tenant_id = ...} 谓词；SELECT 列清单里的 tenant_id 列不算谓词。
     */
    private static boolean hasTenantPredicate(String sql) {
        return TENANT_PREDICATE.matcher(sql).find();
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
