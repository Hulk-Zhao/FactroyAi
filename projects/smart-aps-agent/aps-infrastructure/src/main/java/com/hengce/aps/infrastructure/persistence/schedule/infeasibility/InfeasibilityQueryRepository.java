package com.hengce.aps.infrastructure.persistence.schedule.infeasibility;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 不可行归因的只读重放查询（B1）。
 *
 * <p>只读取 {@code solve_task.config}，用于按 {@code solveTaskId} 重建题面；
 * 不写任何表，写入仍由 B2 负责。</p>
 */
@Repository
public class InfeasibilityQueryRepository {

    /**
     * JDBC 模板。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造查询器。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public InfeasibilityQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取重放所需的任务快照。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 任务快照；不存在时为空
     */
    public Optional<SolveTaskReplayRow> findReplayInput(UUID tenantId, long solveTaskId) {
        String sql = """
                SELECT sandbox_id, strategy_id, engine, status, config::text AS config_json
                  FROM solve_task
                 WHERE tenant_id = ? AND id = ? AND deleted = false
                """;
        List<SolveTaskReplayRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new SolveTaskReplayRow(
                rs.getObject("sandbox_id", UUID.class),
                rs.getObject("strategy_id", UUID.class),
                rs.getString("engine"),
                rs.getString("status"),
                rs.getString("config_json")), tenantId, solveTaskId);
        return rows.stream().findFirst();
    }

    /**
     * 重放输入行。
     *
     * @param sandboxId 场景标识
     * @param strategyId 策略标识
     * @param engine 引擎编码
     * @param status 任务状态
     * @param configJson 提交时的配置 JSON
     */
    public record SolveTaskReplayRow(
            UUID sandboxId,
            UUID strategyId,
            String engine,
            String status,
            String configJson
    ) {
    }
}
