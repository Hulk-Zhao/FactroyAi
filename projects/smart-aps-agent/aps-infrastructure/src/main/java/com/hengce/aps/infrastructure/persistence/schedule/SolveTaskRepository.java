package com.hengce.aps.infrastructure.persistence.schedule;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 求解任务生命周期持久层（M2-SE-03）。
 *
 * <p>所有状态变更都带 {@code AND status IN ('PENDING','RUNNING')} 条件，保证取消/超时与
 * 正常完成并发时只有一个终态写入；先到者生效，后到者返回 0 行不再覆盖。</p>
 */
@Repository
public class SolveTaskRepository {

    /**
     * 生命周期列。
     */
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, sandbox_id, strategy_id, engine, status, score_hard, score_soft,
                   metrics::text AS metrics_json, error_message, idempotency_key,
                   started_at, finished_at, created_at, updated_at
            FROM solve_task
            """;

    /**
     * 行映射。
     */
    private static final RowMapper<SolveTaskRecord> ROW_MAPPER = (rs, rowNum) -> new SolveTaskRecord(
            rs.getLong("id"),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("sandbox_id", UUID.class),
            rs.getObject("strategy_id", UUID.class),
            rs.getString("engine"),
            rs.getString("status"),
            rs.getObject("score_hard", Long.class),
            rs.getObject("score_soft", Long.class),
            rs.getString("metrics_json"),
            rs.getString("error_message"),
            rs.getString("idempotency_key"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    /**
     * JDBC 模板。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public SolveTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 判断场景是否存在（用于提交前校验，避免外键失败被当成 500）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 存在且未删除时为 true
     */
    public boolean sandboxExists(UUID tenantId, UUID sandboxId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sandbox WHERE tenant_id = ? AND id = ? AND deleted = false",
                Long.class, tenantId, sandboxId);
        return count != null && count > 0;
    }

    /**
     * 创建求解任务。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param strategyId 策略标识，可空
     * @param engine 引擎编码（大写）
     * @param configJson 配置 JSON
     * @param idempotencyKey 幂等键，可空
     * @param status 初始状态：PENDING 或 RUNNING
     * @return 求解任务主键
     */
    public long create(UUID tenantId, UUID sandboxId, UUID strategyId, String engine,
                       String configJson, String idempotencyKey, String status) {
        String sql = """
                INSERT INTO solve_task (tenant_id, sandbox_id, strategy_id, engine, status, config,
                                        idempotency_key, started_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, CASE WHEN ? = 'RUNNING' THEN now() ELSE NULL END)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                tenantId, sandboxId, strategyId, engine, status, configJson, idempotencyKey, status);
        if (id == null) {
            throw new IllegalStateException("solve_task 插入未返回主键");
        }
        return id;
    }

    /**
     * 按主键查询。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 求解任务
     */
    public Optional<SolveTaskRecord> findById(UUID tenantId, long solveTaskId) {
        List<SolveTaskRecord> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ? AND deleted = false",
                ROW_MAPPER, tenantId, solveTaskId);
        return rows.stream().findFirst();
    }

    /**
     * 按幂等键查询；V1 的 {@code uq_solve_task_idempotency(tenant_id, idempotency_key)} 保证租户内唯一。
     *
     * @param tenantId 租户标识
     * @param idempotencyKey 幂等键
     * @return 已存在的求解任务
     */
    public Optional<SolveTaskRecord> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        List<SolveTaskRecord> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND idempotency_key = ? AND deleted = false"
                        + " ORDER BY id LIMIT 1",
                ROW_MAPPER, tenantId, idempotencyKey);
        return rows.stream().findFirst();
    }

    /**
     * PENDING → RUNNING。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 受影响行数；0 表示已被取消或已终态
     */
    public int markRunning(UUID tenantId, long solveTaskId) {
        String sql = """
                UPDATE solve_task
                   SET status = 'RUNNING', started_at = COALESCE(started_at, now()), updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status = 'PENDING' AND deleted = false
                """;
        return jdbcTemplate.update(sql, tenantId, solveTaskId);
    }

    /**
     * 合并求解指标（JSONB {@code ||}，右侧覆盖左侧）。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @param metricsJson 指标 JSON
     * @return 受影响行数；0 表示已终态
     */
    public int mergeMetrics(UUID tenantId, long solveTaskId, String metricsJson) {
        String sql = """
                UPDATE solve_task
                   SET metrics = metrics || ?::jsonb, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status IN ('PENDING', 'RUNNING') AND deleted = false
                """;
        return jdbcTemplate.update(sql, metricsJson, tenantId, solveTaskId);
    }

    /**
     * 写入终态；仅当任务仍处于 PENDING/RUNNING 时生效。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @param status 终态：SOLVED/FAILED/CANCELLED/TIMEOUT
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metricsJson 指标 JSON（与既有 metrics 合并）
     * @param errorMessage 失败/取消说明，可空
     * @return 受影响行数；0 表示已有并发终态
     */
    public int finish(UUID tenantId, long solveTaskId, String status, Long scoreHard, Long scoreSoft,
                      String metricsJson, String errorMessage) {
        String sql = """
                UPDATE solve_task
                   SET status = ?, score_hard = ?, score_soft = ?, finished_at = now(),
                       metrics = metrics || ?::jsonb, error_message = ?, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND status IN ('PENDING', 'RUNNING') AND deleted = false
                """;
        return jdbcTemplate.update(sql, status, scoreHard, scoreSoft,
                metricsJson == null ? "{}" : metricsJson, errorMessage, tenantId, solveTaskId);
    }

    /**
     * 场景内活动任务数（用于观测“每场景最多 1 个 RUNNING”）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 活动任务数
     */
    public long countActiveBySandbox(UUID tenantId, UUID sandboxId) {
        String sql = """
                SELECT COUNT(*) FROM solve_task
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                   AND status IN ('PENDING', 'RUNNING')
                """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, tenantId, sandboxId);
        return count == null ? 0L : count;
    }

    /**
     * 求解任务行。
     *
     * @param id 主键
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param strategyId 策略标识，可空
     * @param engine 引擎编码
     * @param status 状态
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metricsJson 指标 JSON 文本
     * @param errorMessage 错误说明，可空
     * @param idempotencyKey 幂等键，可空
     * @param startedAt 开始时间，可空
     * @param finishedAt 结束时间，可空
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record SolveTaskRecord(
            long id,
            UUID tenantId,
            UUID sandboxId,
            UUID strategyId,
            String engine,
            String status,
            Long scoreHard,
            Long scoreSoft,
            String metricsJson,
            String errorMessage,
            String idempotencyKey,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    /**
     * 当前 UTC 时间；供服务层写入终止时间时复用。
     *
     * @return UTC 偏移时间
     */
    public static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
