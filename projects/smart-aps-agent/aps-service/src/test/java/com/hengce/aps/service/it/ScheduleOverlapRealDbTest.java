package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 增量验收硬线：整张 {@code task} 表按资源两两比较，区间重叠必须为 0 对。
 *
 * <p>校验口径与生产落库守卫完全一致（{@link ScheduleRepository#findResourceOverlaps}）：
 * 占用区间按 {@code [COALESCE(setup_start_time, start_time), end_time)} 半开比较，
 * 因此首尾相接（前一条 12:00 结束、后一条 12:00 开始）不算重叠。</p>
 *
 * <p>不装载 Spring 上下文，直接按真库参数构造 {@link ScheduleRepository}，
 * 只验证新增的全表重叠查询本身；本次数据全部带测试前缀并在用例结束后硬删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class ScheduleOverlapRealDbTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String PREFIX =
            "QA-OVERLAP-" + Long.toHexString(System.currentTimeMillis()) + "-";

    private JdbcTemplate jdbc;

    private ScheduleRepository repository;

    private UUID sandboxId;

    private UUID orderId;

    private final OffsetDateTime base =
            OffsetDateTime.of(2030, 1, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(RealDbEnvironment.jdbcUrl());
        dataSource.setUsername(RealDbEnvironment.username());
        dataSource.setPassword(RealDbEnvironment.password());
        jdbc = new JdbcTemplate(dataSource);
        repository = new ScheduleRepository(jdbc);

        sandboxId = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot) "
                        + "VALUES (?, ?, ?, 'WHAT_IF', '{}'::jsonb)",
                sandboxId, TENANT_ID, PREFIX + "SBX");

        UUID materialId = UUID.randomUUID();
        jdbc.update("INSERT INTO material (id, tenant_id, code, name, type, unit) "
                + "VALUES (?, ?, ?, ?, 'RAW', 'PCS')",
                materialId, TENANT_ID, PREFIX + "MAT", PREFIX + "MAT");

        orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, order_no, product_id, qty, due_date) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, TENANT_ID, PREFIX + "ORD", materialId, BigDecimal.TEN,
                LocalDate.of(2030, 2, 1));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM task WHERE sandbox_id = ?", sandboxId);
        jdbc.update("DELETE FROM sandbox WHERE id = ?", sandboxId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM material WHERE code LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM resource WHERE code LIKE ?", PREFIX + "%");
    }

    @Test
    void fullTableOverlapQueryFindsNoPairAfterAdjustment() {
        UUID resourceId = UUID.randomUUID();
        jdbc.update("INSERT INTO resource (id, tenant_id, code, name, type) "
                + "VALUES (?, ?, ?, ?, 'PRIMARY')",
                resourceId, TENANT_ID, PREFIX + "RES", PREFIX + "RES");

        long firstId = insertTask(resourceId, 10, base, base.plusHours(4));
        long secondId = insertTask(resourceId, 20, base.plusHours(3), base.plusHours(7));

        List<ScheduleRepository.OverlapRow> before =
                repository.findResourceOverlaps(TENANT_ID, sandboxId, List.of());
        assertEquals(1, before.size(), "构造的重叠必须被全表校验抓到");

        jdbc.update("UPDATE task SET start_time = ?, end_time = ?, setup_start_time = ?, setup_end_time = ? "
                        + "WHERE id = ?",
                base.plusHours(4), base.plusHours(8), base.plusHours(4), base.plusHours(4), secondId);

        List<ScheduleRepository.OverlapRow> after =
                repository.findResourceOverlaps(TENANT_ID, sandboxId, List.of());
        assertTrue(after.isEmpty(), "移动后整表重叠必须为 0 对");

        List<ScheduleRepository.OverlapRow> scoped =
                repository.findResourceOverlaps(TENANT_ID, sandboxId, List.of(firstId, secondId));
        assertTrue(scoped.isEmpty(), "本次写入任务参与的重叠对必须为 0 对");
    }

    private long insertTask(UUID resourceId, int operationIndex,
                            OffsetDateTime start, OffsetDateTime end) {
        Long id = jdbc.queryForObject(
                "INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, resource_id, "
                        + "start_time, end_time, setup_start_time, setup_end_time, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED') RETURNING id",
                Long.class, TENANT_ID, orderId, sandboxId, operationIndex, resourceId,
                start, end, start, start);
        assertTrue(id != null, "任务必须插入成功");
        return id;
    }
}
