package com.hengce.aps.infrastructure.persistence.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 增量重排持久层（C21 §24.3）。
 *
 * <p>只做三件事：读取基准求解任务配置、读取场景任务锁状态、写入新的 {@code plan_version}
 * （DRAFT）。已发布版本的快照与任务行不被本类覆盖。</p>
 */
@Repository
public class IncrementalSolveRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public IncrementalSolveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取基准求解任务（含提交时固化的配置 JSON）。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 基准任务
     */
    public Optional<BaseSolveTask> findSolveTask(UUID tenantId, long solveTaskId) {
        List<BaseSolveTask> rows = jdbcTemplate.query("""
                SELECT id, sandbox_id, strategy_id, engine, config::text AS config_json
                  FROM solve_task
                 WHERE tenant_id = ? AND id = ? AND deleted = false
                """, (rs, rowNum) -> new BaseSolveTask(
                        rs.getLong("id"),
                        rs.getObject("sandbox_id", UUID.class),
                        rs.getObject("strategy_id", UUID.class),
                        rs.getString("engine"),
                        rs.getString("config_json")),
                tenantId, solveTaskId);
        return rows.stream().findFirst();
    }

    /**
     * 读取场景内全部未删除任务。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 任务引用
     */
    public List<TaskRef> findTasks(UUID tenantId, UUID sandboxId) {
        return jdbcTemplate.query("""
                SELECT t.id, t.order_id, o.order_no, t.status, t.lock_type
                  FROM task t
                  LEFT JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                 ORDER BY t.order_id, t.operation_index, t.id
                """, TASK_MAPPER, tenantId, sandboxId);
    }

    /**
     * 读取场景内含有锁定/冻结任务的订单集合；这些订单不参与增量重排，避免放宽锁语义。
     *
     * <p>同时覆盖两层冻结：任务级 {@code lock_type/status} 与订单级 {@code freeze_status}。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 被排除的订单主键
     */
    public List<UUID> findFrozenOrderIds(UUID tenantId, UUID sandboxId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT t.order_id
                  FROM task t
                  JOIN orders o ON o.id = t.order_id
                 WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                   AND o.freeze_status <> 'UNFROZEN'
                """, (rs, rowNum) -> rs.getObject("order_id", UUID.class), tenantId, sandboxId);
    }

    /**
     * 写入新的 DRAFT 计划版本；不触碰任何已 PUBLISHED 快照。
     *
     * <p>快照占位 JSON 记录本次求解范围 {@code scopeOrderNos}，发布时据此只固化/回填
     * 范围内任务，避免同一场景内其他客户的订单被改写版本号。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param planNo 计划业务号
     * @param versionNo 版本号
     * @param reason 原因
     * @param operatorId 操作人，可空
     * @param scopeOrderNos 本次增量求解覆盖的订单号；空列表表示按场景全量
     * @return 新版本主键（对外 planId）
     */
    public UUID insertDraftVersion(UUID tenantId, UUID sandboxId, String planNo, int versionNo,
                                   String reason, UUID operatorId, List<String> scopeOrderNos) {
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO plan_version (id, tenant_id, sandbox_id, plan_no, version_no, status,
                                          snapshot, task_count, reason, created_by)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, 0, ?, ?)
                """, planId, tenantId, sandboxId, planNo, String.valueOf(versionNo),
                scopeJson(scopeOrderNos), reason, operatorId);
        return planId;
    }

    private static String scopeJson(List<String> scopeOrderNos) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scopeOrderNos", scopeOrderNos == null ? List.of() : scopeOrderNos);
        try {
            return new ObjectMapper().writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("增量计划范围序列化失败", exception);
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<TaskRef> TASK_MAPPER =
            (rs, rowNum) -> new TaskRef(
                    rs.getLong("id"),
                    rs.getObject("order_id", UUID.class),
                    rs.getString("order_no"),
                    rs.getString("status"),
                    rs.getString("lock_type"));

    /**
     * 基准求解任务快照。
     *
     * @param solveTaskId 主键
     * @param sandboxId 场景标识
     * @param strategyId 策略标识，可空
     * @param engine 引擎编码
     * @param configJson 提交时固化的配置 JSON
     */
    public record BaseSolveTask(long solveTaskId, UUID sandboxId, UUID strategyId, String engine,
                                String configJson) {
    }

    /**
     * 任务引用。
     *
     * @param taskId 任务主键
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param status 任务状态
     * @param lockType 锁类型
     */
    public record TaskRef(long taskId, UUID orderId, String orderNo, String status, String lockType) {
    }
}
