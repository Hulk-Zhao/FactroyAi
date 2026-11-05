package com.hengce.aps.infrastructure.persistence.schedule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 甘特人工调整持久层（C22 / V1 {@code task_adjustment}）。
 *
 * <p>只读写 {@code task}、{@code orders}（只读冻结字段）与 {@code task_adjustment}；
 * 所有 SQL 显式携带 {@code tenant_id}。任务更新一律带 {@code version = ?} 条件，
 * 返回 0 行即表示版本已被并发修改，由服务层映射为 40901。</p>
 */
@Repository
public class TaskAdjustmentRepository {

    private static final String TASK_SELECT = """
            SELECT t.id, t.order_id, o.order_no, o.freeze_status, o.ext_attrs::text AS order_attrs,
                   t.sandbox_id, t.operation_index, t.operation_template_id, t.resource_id,
                   r.code AS resource_code, t.secondary_resource_ids::text AS secondary_json,
                   t.setup_start_time, t.setup_end_time, t.start_time, t.end_time, t.qty,
                   t.status, t.sequence_in_resource, t.version, t.lock_type,
                   t.locked_from, t.locked_to, t.locked_resource_ids::text AS locked_resources_json
              FROM task t
              JOIN orders o ON o.id = t.order_id
              LEFT JOIN resource r ON r.id = t.resource_id
            """;

    private static final RowMapper<AdjustTaskRow> TASK_MAPPER = (rs, rowNum) -> new AdjustTaskRow(
            rs.getLong("id"),
            rs.getObject("order_id", UUID.class),
            rs.getString("order_no"),
            rs.getString("freeze_status"),
            rs.getString("order_attrs"),
            rs.getObject("sandbox_id", UUID.class),
            rs.getInt("operation_index"),
            rs.getObject("operation_template_id", UUID.class),
            rs.getObject("resource_id", UUID.class),
            rs.getString("resource_code"),
            rs.getString("secondary_json"),
            rs.getObject("setup_start_time", OffsetDateTime.class),
            rs.getObject("setup_end_time", OffsetDateTime.class),
            rs.getObject("start_time", OffsetDateTime.class),
            rs.getObject("end_time", OffsetDateTime.class),
            rs.getObject("qty", java.math.BigDecimal.class),
            rs.getString("status"),
            (Integer) rs.getObject("sequence_in_resource"),
            rs.getInt("version"),
            rs.getString("lock_type"),
            rs.getObject("locked_from", OffsetDateTime.class),
            rs.getObject("locked_to", OffsetDateTime.class),
            rs.getString("locked_resources_json"));

    private static final RowMapper<AdjustmentRow> ADJUSTMENT_MAPPER = (rs, rowNum) -> new AdjustmentRow(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getObject("sandbox_id", UUID.class),
            rs.getString("adjust_type"),
            rs.getString("before_value"),
            rs.getString("after_value"),
            rs.getString("reason"),
            rs.getBoolean("undone"),
            rs.getObject("undo_of_id", Long.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public TaskAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按主键读取任务（含订单冻结状态与扩展属性原文）。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @return 任务行
     */
    public Optional<AdjustTaskRow> findTask(UUID tenantId, long taskId) {
        List<AdjustTaskRow> rows = jdbcTemplate.query(
                TASK_SELECT + " WHERE t.tenant_id = ? AND t.id = ? AND t.deleted = false",
                TASK_MAPPER, tenantId, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 读取同一订单在同一场景内的全部任务，用于工序前后依赖校验。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param orderId 订单主键
     * @return 该订单任务，按工序号升序
     */
    public List<AdjustTaskRow> findOrderTasks(UUID tenantId, UUID sandboxId, UUID orderId) {
        return jdbcTemplate.query(
                TASK_SELECT + " WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.order_id = ?"
                        + " AND t.deleted = false ORDER BY t.operation_index, t.id",
                TASK_MAPPER, tenantId, sandboxId, orderId);
    }

    /**
     * 查询目标资源上与给定时间窗重叠的其它任务。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param resourceId 目标资源主键
     * @param excludeTaskId 排除的任务主键（被调整任务本身）
     * @param from 时间窗开始（含）
     * @param to 时间窗结束（不含）
     * @return 重叠任务
     */
    public List<AdjustTaskRow> findOverlappingTasks(UUID tenantId, UUID sandboxId, UUID resourceId,
                                                    long excludeTaskId, OffsetDateTime from,
                                                    OffsetDateTime to) {
        return jdbcTemplate.query(
                TASK_SELECT + " WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.resource_id = ?"
                        + " AND t.id <> ? AND t.deleted = false"
                        + " AND t.start_time < ? AND t.end_time > ? ORDER BY t.start_time, t.id",
                TASK_MAPPER, tenantId, sandboxId, resourceId, excludeTaskId, to, from);
    }

    /**
     * 按资源编码读取资源主键与绑定日历。
     *
     * @param tenantId 租户标识
     * @param code 资源编码
     * @return 资源引用
     */
    public Optional<ResourceRef> findResourceByCode(UUID tenantId, String code) {
        List<ResourceRef> rows = jdbcTemplate.query(
                "SELECT id, code, calendar_id FROM resource"
                        + " WHERE tenant_id = ? AND code = ? AND deleted = false",
                (rs, rowNum) -> new ResourceRef(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getObject("calendar_id", UUID.class)),
                tenantId, code);
        return rows.stream().findFirst();
    }

    /**
     * 乐观锁更新任务的时间、资源、状态与锁字段；版本号自增 1。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @param expectedVersion 期望版本
     * @param resourceId 主资源主键，可空
     * @param startTime 加工开始，可空
     * @param endTime 加工结束，可空
     * @param setupStartTime 换型开始，可空
     * @param setupEndTime 换型结束，可空
     * @param status 任务状态
     * @param lockType 锁类型
     * @param lockedFrom 锁窗开始，可空
     * @param lockedTo 锁窗结束，可空
     * @param lockedResourcesJson 锁定资源主键数组 JSON
     * @param qty 新数量；为空表示保持原值（MOVE/LOCK/FREEZE/UNFREEZE）
     * @param sequenceInResource 新资源内顺序；为空表示保持原值
     * @return 受影响行数；0 表示版本冲突
     */
    public int updateTask(UUID tenantId, long taskId, int expectedVersion, UUID resourceId,
                          OffsetDateTime startTime, OffsetDateTime endTime,
                          OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                          String status, String lockType, OffsetDateTime lockedFrom,
                          OffsetDateTime lockedTo, String lockedResourcesJson,
                          java.math.BigDecimal qty, Integer sequenceInResource) {
        String sql = """
                UPDATE task
                   SET resource_id = ?, start_time = ?, end_time = ?, setup_start_time = ?, setup_end_time = ?,
                       status = ?, lock_type = ?, locked_from = ?, locked_to = ?, locked_resource_ids = ?::jsonb,
                       qty = COALESCE(?, qty), sequence_in_resource = COALESCE(?, sequence_in_resource),
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND version = ? AND deleted = false
                """;
        return jdbcTemplate.update(sql, resourceId, startTime, endTime, setupStartTime, setupEndTime,
                status, lockType, lockedFrom, lockedTo, lockedResourcesJson, qty, sequenceInResource,
                tenantId, taskId, expectedVersion);
    }

    /**
     * 资源内顺序顺延：把 {@code sequence_in_resource > afterSequence} 的任务整体平移。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param resourceId 资源主键
     * @param afterSequence 基准顺序（不含）
     * @param delta 平移量，可为负
     * @return 受影响行数
     */
    public int shiftSequence(UUID tenantId, UUID sandboxId, UUID resourceId,
                             int afterSequence, int delta) {
        if (resourceId == null || delta == 0) {
            return 0;
        }
        return jdbcTemplate.update("""
                UPDATE task
                   SET sequence_in_resource = sequence_in_resource + ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND sandbox_id = ? AND resource_id = ? AND deleted = false
                   AND sequence_in_resource IS NOT NULL AND sequence_in_resource > ?
                """, delta, tenantId, sandboxId, resourceId, afterSequence);
    }

    /**
     * 新建 SPLIT 子任务（{@code status=PLANNED}、{@code version=1}）。
     *
     * @param tenantId 租户标识
     * @param row 子任务字段
     * @param operatorId 操作人，可空
     * @return 新任务主键
     */
    public long insertSplitTask(UUID tenantId, NewSplitTask row, UUID operatorId) {
        String sql = """
                INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, operation_template_id,
                                  resource_id, secondary_resource_ids, start_time, end_time,
                                  setup_start_time, setup_end_time, qty, status, sequence_in_resource,
                                  version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, NULL, NULL, ?, 'PLANNED', ?, 1, ?, ?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class, tenantId, row.orderId(), row.sandboxId(),
                row.operationIndex(), row.operationTemplateId(), row.resourceId(), row.secondaryJson(),
                row.startTime(), row.endTime(), row.qty(), row.sequenceInResource(), operatorId, operatorId);
        if (id == null) {
            throw new IllegalStateException("task 插入未返回主键");
        }
        return id;
    }

    /**
     * 软删除任务（MERGE 合并方 / SPLIT 撤销子任务），不物理删除历史。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @return 受影响行数
     */
    public int softDeleteTask(UUID tenantId, long taskId) {
        return jdbcTemplate.update("""
                UPDATE task SET deleted = true, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND deleted = false
                """, tenantId, taskId);
    }

    /**
     * 恢复被软删的任务（SPLIT 重做）。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @return 受影响行数
     */
    public int undeleteTask(UUID tenantId, long taskId) {
        return jdbcTemplate.update("""
                UPDATE task SET deleted = false, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND deleted = true
                """, tenantId, taskId);
    }

    /**
     * 恢复 MERGE 合并方的原始行（撤销）。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @param qty 原始数量
     * @param startTime 原始开始
     * @param endTime 原始结束
     * @param setupStartTime 原始换型开始
     * @param setupEndTime 原始换型结束
     * @param sequenceInResource 原始资源内顺序
     * @return 受影响行数
     */
    public int restoreMergedTask(UUID tenantId, long taskId, java.math.BigDecimal qty,
                                 OffsetDateTime startTime, OffsetDateTime endTime,
                                 OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                                 Integer sequenceInResource) {
        return jdbcTemplate.update("""
                UPDATE task
                   SET deleted = false, qty = ?, start_time = ?, end_time = ?,
                       setup_start_time = ?, setup_end_time = ?, sequence_in_resource = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ?
                """, qty, startTime, endTime, setupStartTime, setupEndTime, sequenceInResource,
                tenantId, taskId);
    }

    /**
     * 追加一条调整历史。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @param sandboxId 场景标识
     * @param adjustType 调整类型
     * @param beforeJson 调整前快照
     * @param afterJson 调整后快照
     * @param reason 原因，可空
     * @param operatorId 操作人，可空
     * @return 调整记录主键
     */
    public long insertAdjustment(UUID tenantId, long taskId, UUID sandboxId, String adjustType,
                                 String beforeJson, String afterJson, String reason, UUID operatorId) {
        String sql = """
                INSERT INTO task_adjustment (tenant_id, task_id, sandbox_id, adjust_type, before_value,
                                             after_value, reason, operator_id, undone, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, false, ?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class, tenantId, taskId, sandboxId, adjustType,
                beforeJson, afterJson, reason, operatorId, operatorId);
        if (id == null) {
            throw new IllegalStateException("task_adjustment 插入未返回主键");
        }
        return id;
    }

    /**
     * 按主键读取调整记录。
     *
     * @param tenantId 租户标识
     * @param adjustmentId 调整记录主键
     * @return 调整记录
     */
    public Optional<AdjustmentRow> findAdjustment(UUID tenantId, long adjustmentId) {
        List<AdjustmentRow> rows = jdbcTemplate.query("""
                SELECT id, task_id, sandbox_id, adjust_type, before_value::text AS before_value,
                       after_value::text AS after_value, reason, undone, undo_of_id, created_at
                  FROM task_adjustment
                 WHERE tenant_id = ? AND id = ?
                """, ADJUSTMENT_MAPPER, tenantId, adjustmentId);
        return rows.stream().findFirst();
    }

    /**
     * 读取任务最近一条未撤销的调整记录。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @return 最近未撤销记录
     */
    public Optional<AdjustmentRow> findLatestActive(UUID tenantId, long taskId) {
        List<AdjustmentRow> rows = jdbcTemplate.query("""
                SELECT id, task_id, sandbox_id, adjust_type, before_value::text AS before_value,
                       after_value::text AS after_value, reason, undone, undo_of_id, created_at
                  FROM task_adjustment
                 WHERE tenant_id = ? AND task_id = ? AND undone = false
                 ORDER BY id DESC LIMIT 1
                """, ADJUSTMENT_MAPPER, tenantId, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 判断给定调整记录之后是否还有该任务的新调整（用于 redo 的前置校验）。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @param adjustmentId 调整记录主键
     * @return 是否存在更晚的调整
     */
    public boolean existsLaterAdjustment(UUID tenantId, long taskId, long adjustmentId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM task_adjustment
                 WHERE tenant_id = ? AND task_id = ? AND id > ?
                """, Integer.class, tenantId, taskId, adjustmentId);
        return count != null && count > 0;
    }

    /**
     * 标记调整记录的撤销状态。
     *
     * @param tenantId 租户标识
     * @param adjustmentId 调整记录主键
     * @param undone 是否已撤销
     * @return 受影响行数
     */
    public int markUndone(UUID tenantId, long adjustmentId, boolean undone) {
        return jdbcTemplate.update("UPDATE task_adjustment SET undone = ? WHERE tenant_id = ? AND id = ?",
                undone, tenantId, adjustmentId);
    }

    /**
     * 分页读取任务调整历史。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @param limit 页大小
     * @param offset 偏移
     * @return 调整记录，按主键倒序
     */
    public List<AdjustmentRow> findHistory(UUID tenantId, long taskId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, task_id, sandbox_id, adjust_type, before_value::text AS before_value,
                       after_value::text AS after_value, reason, undone, undo_of_id, created_at
                  FROM task_adjustment
                 WHERE tenant_id = ? AND task_id = ?
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, ADJUSTMENT_MAPPER, tenantId, taskId, limit, offset);
    }

    /**
     * 统计任务调整历史条数。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @return 条数
     */
    public int countHistory(UUID tenantId, long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM task_adjustment WHERE tenant_id = ? AND task_id = ?",
                Integer.class, tenantId, taskId);
        return count == null ? 0 : count;
    }

    /**
     * 任务读取行（C22 调整所需字段）。
     *
     * @param taskId 任务主键
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param orderFreezeStatus 订单冻结状态
     * @param orderExtAttrs 订单扩展属性 JSON 原文
     * @param sandboxId 场景标识
     * @param operationIndex 工序序号
     * @param operationTemplateId 工序模版主键
     * @param resourceId 主资源主键
     * @param resourceCode 主资源编码
     * @param secondaryJson 辅助资源主键 JSON 原文
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param qty 数量
     * @param status 任务状态
     * @param sequenceInResource 资源内顺序
     * @param version 乐观锁版本
     * @param lockType 锁类型
     * @param lockedFrom 锁窗开始
     * @param lockedTo 锁窗结束
     * @param lockedResourcesJson 锁定资源主键 JSON 原文
     */
    public record AdjustTaskRow(long taskId, UUID orderId, String orderNo, String orderFreezeStatus,
                                String orderExtAttrs, UUID sandboxId, int operationIndex,
                                UUID operationTemplateId, UUID resourceId, String resourceCode,
                                String secondaryJson, OffsetDateTime setupStartTime,
                                OffsetDateTime setupEndTime, OffsetDateTime startTime,
                                OffsetDateTime endTime, java.math.BigDecimal qty, String status,
                                Integer sequenceInResource, int version, String lockType,
                                OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                                String lockedResourcesJson) {
    }

    /**
     * SPLIT 新建子任务字段。
     *
     * @param orderId 订单主键
     * @param sandboxId 场景标识
     * @param operationIndex 工序序号
     * @param operationTemplateId 工序模版主键
     * @param resourceId 资源主键
     * @param secondaryJson 辅助资源 JSON 原文
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param qty 数量
     * @param sequenceInResource 资源内顺序
     */
    public record NewSplitTask(UUID orderId, UUID sandboxId, int operationIndex,
                               UUID operationTemplateId, UUID resourceId, String secondaryJson,
                               OffsetDateTime startTime, OffsetDateTime endTime,
                               java.math.BigDecimal qty, Integer sequenceInResource) {
    }

    /**
     * 调整历史行。
     *
     * @param id 主键
     * @param taskId 任务主键
     * @param sandboxId 场景标识
     * @param adjustType 调整类型
     * @param beforeJson 调整前快照 JSON
     * @param afterJson 调整后快照 JSON
     * @param reason 原因
     * @param undone 是否已撤销
     * @param undoOfId 撤销来源记录主键
     * @param createdAt 创建时间
     */
    public record AdjustmentRow(long id, long taskId, UUID sandboxId, String adjustType,
                                String beforeJson, String afterJson, String reason, boolean undone,
                                Long undoOfId, OffsetDateTime createdAt) {
    }

    /**
     * 资源引用。
     *
     * @param resourceId 资源主键
     * @param code 资源编码
     * @param calendarId 绑定日历主键，可空
     */
    public record ResourceRef(UUID resourceId, String code, UUID calendarId) {
    }
}
