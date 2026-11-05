package com.hengce.aps.infrastructure.persistence.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 排产链持久层：订单只读查询、冻结任务读取、求解任务/任务/归因写入与甘特查询。
 *
 * <p>本类使用 {@link JdbcTemplate} 而非 MyBatis Mapper：{@code task}/{@code solve_task} 为
 * BIGSERIAL 高频表，需要 {@code RETURNING id} 精确回填；同时避免复制其它业务流的实体。
 * 所有 SQL 显式携带 {@code tenant_id}，不依赖租户拦截器。</p>
 */
@Repository
public class ScheduleRepository {

    /**
     * JSON 序列化器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * JDBC 模板。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public ScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询参与排产的订单。
     *
     * @param tenantId 租户标识
     * @param orderNos 指定订单号；空表示场景内全部已确认订单
     * @param onlyConfirmed 是否只取 CONFIRMED 状态
     * @return 订单读取行，按交期、订单号升序
     */
    public List<OrderSourceRow> findOrders(UUID tenantId, List<String> orderNos, boolean onlyConfirmed) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.order_no, o.product_id, m.code AS material_code,
                       mc.code AS product_family, o.qty, o.due_date,
                       o.priority, o.freeze_status, o.routing_id, o.status
                FROM orders o
                JOIN material m ON m.id = o.product_id
                LEFT JOIN material_category mc ON mc.id = m.category_id
                WHERE o.tenant_id = ? AND o.deleted = false
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (onlyConfirmed) {
            sql.append(" AND o.status = 'CONFIRMED'");
        }
        if (orderNos != null && !orderNos.isEmpty()) {
            sql.append(" AND o.order_no IN (").append(placeholders(orderNos.size())).append(')');
            args.addAll(orderNos);
        }
        sql.append(" ORDER BY o.due_date, o.order_no");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OrderSourceRow(
                rs.getObject("id", UUID.class),
                rs.getString("order_no"),
                rs.getObject("product_id", UUID.class),
                rs.getString("material_code"),
                rs.getString("product_family"),
                rs.getBigDecimal("qty"),
                rs.getObject("due_date", LocalDate.class),
                rs.getString("priority"),
                rs.getString("freeze_status"),
                rs.getObject("routing_id", UUID.class),
                rs.getString("status")), args.toArray());
    }

    /**
     * 查询与求解窗口相交的冻结/锁定任务。
     *
     * <p><b>窗口口径</b>（与 {@link #findOrdersWithExistingTasks} 完全一致）：只读取
     * {@code end_time > horizonStart} 且 {@code coalesce(setup_start_time, start_time) < horizonEnd}
     * 的既有任务，即“与 horizon 相交”。窗口外的既有任务不影响本次窗口内的排产，
     * 不应作为 pinned 项进入题面——否则会出现“pinned 在窗口起点之前、日历必然违反”的
     * 结构性不可解（{@code calendarAvailability}）。窗口外且已结束的 FROZEN 前序所代表的
     * 先后关系，改由订单级下界 {@link #findFrozenOperationsBeforeWindow} 表达。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param horizonStart 求解 horizon 起点
     * @param horizonEnd 求解 horizon 终点
     * @return 与窗口相交的冻结任务
     */
    public List<FrozenTaskRow> findFrozenTasks(
            UUID tenantId, UUID sandboxId, OffsetDateTime horizonStart, OffsetDateTime horizonEnd) {
        String sql = """
                SELECT t.id, t.order_id, o.order_no, t.operation_index, t.operation_template_id,
                       t.resource_id, r.code AS resource_code, t.secondary_resource_ids::text AS secondary_json,
                       t.setup_start_time, t.setup_end_time, t.start_time, t.end_time, t.qty,
                       t.status, t.version, t.lock_type, t.locked_from, t.locked_to,
                       t.locked_resource_ids::text AS locked_json, t.created_at
                FROM task t
                JOIN orders o ON o.id = t.order_id
                LEFT JOIN resource r ON r.id = t.resource_id
                WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                  AND (t.status = 'FROZEN' OR t.lock_type <> 'NONE')
                  AND t.end_time > ?
                  AND coalesce(t.setup_start_time, t.start_time) < ?
                ORDER BY t.start_time, t.id
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new FrozenTaskRow(
                rs.getLong("id"),
                rs.getObject("order_id", UUID.class),
                rs.getString("order_no"),
                rs.getInt("operation_index"),
                rs.getObject("operation_template_id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                rs.getString("resource_code"),
                parseUuidArray(rs.getString("secondary_json")),
                rs.getObject("setup_start_time", OffsetDateTime.class),
                rs.getObject("setup_end_time", OffsetDateTime.class),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                rs.getBigDecimal("qty"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getString("lock_type"),
                rs.getObject("locked_from", OffsetDateTime.class),
                rs.getObject("locked_to", OffsetDateTime.class),
                parseUuidArray(rs.getString("locked_json")),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, sandboxId, horizonStart, horizonEnd);
    }

    /**
     * 查询“落在求解窗口起点之前”的 FROZEN 工序及其结束时间（按订单 + 工序）。
     *
     * <p>{@link #findFrozenTasks} 按窗口过滤后，这些 FROZEN 任务不会作为 pinned 项进入题面；
     * 但它们代表已完成的工序：装配侧据此把对应工序移出题面，并用同一订单的最大结束时间
     * 作为剩余工序的硬下界，避免求解器把后续工序排到已完工工序之前（0hard 但现实不可执行）。</p>
     *
     * <p>口径：仅取 {@code status='FROZEN'} 或 {@code lock_type='FROZEN'}（真正不可移动的完成事实），
     * 且 {@code end_time <= horizonStart}（与窗口无交集）。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param horizonStart 求解 horizon 起点
     * @return 窗口外已完成工序行
     */
    public List<CompletedFrozenOperationRow> findFrozenOperationsBeforeWindow(
            UUID tenantId, UUID sandboxId, OffsetDateTime horizonStart) {
        String sql = """
                SELECT t.order_id, t.operation_index, MAX(t.end_time) AS max_end_time
                FROM task t
                WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                  AND (t.status = 'FROZEN' OR t.lock_type = 'FROZEN')
                  AND t.end_time <= ?
                GROUP BY t.order_id, t.operation_index
                ORDER BY t.order_id, t.operation_index
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new CompletedFrozenOperationRow(
                rs.getObject("order_id", UUID.class),
                rs.getInt("operation_index"),
                rs.getObject("max_end_time", OffsetDateTime.class)),
                tenantId, sandboxId, horizonStart);
    }

    /**
     * 读取指定订单的全部既有任务（增量重排的 pinned 基线）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param orderIds 订单主键集合
     * @return 任务行；订单集合为空时返回空列表
     */
    public List<FrozenTaskRow> findTasksByOrders(UUID tenantId, UUID sandboxId, List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT t.id, t.order_id, o.order_no, t.operation_index, t.operation_template_id,
                       t.resource_id, r.code AS resource_code, t.secondary_resource_ids::text AS secondary_json,
                       t.setup_start_time, t.setup_end_time, t.start_time, t.end_time, t.qty,
                       t.status, t.version, t.lock_type, t.locked_from, t.locked_to,
                       t.locked_resource_ids::text AS locked_json, t.created_at
                FROM task t
                JOIN orders o ON o.id = t.order_id
                LEFT JOIN resource r ON r.id = t.resource_id
                WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                  AND t.order_id IN (%s)
                ORDER BY t.start_time, t.id
                """.formatted(String.join(",", Collections.nCopies(orderIds.size(), "?")));
        Object[] args = new Object[orderIds.size() + 2];
        args[0] = tenantId;
        args[1] = sandboxId;
        for (int index = 0; index < orderIds.size(); index++) {
            args[index + 2] = orderIds.get(index);
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new FrozenTaskRow(
                rs.getLong("id"),
                rs.getObject("order_id", UUID.class),
                rs.getString("order_no"),
                rs.getInt("operation_index"),
                rs.getObject("operation_template_id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                rs.getString("resource_code"),
                parseUuidArray(rs.getString("secondary_json")),
                rs.getObject("setup_start_time", OffsetDateTime.class),
                rs.getObject("setup_end_time", OffsetDateTime.class),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                rs.getBigDecimal("qty"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getString("lock_type"),
                rs.getObject("locked_from", OffsetDateTime.class),
                rs.getObject("locked_to", OffsetDateTime.class),
                parseUuidArray(rs.getString("locked_json")),
                rs.getObject("created_at", OffsetDateTime.class)), args);
    }

    /**
     * 读取场景内已有任务、但不属于本次求解范围的订单。
     *
     * <p>这些订单的既有任务是本次求解的“既有占用”：装配时必须把它们作为不可移动的
     * pinned 项一起装入题面，否则求解器看不见别的订单已占用的资源与时段，会排出与
     * 库内既有任务真实重叠的结果（C21 §24.3）。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param excludeOrderIds 本次求解的订单主键集合，可空
     * @param horizonStart 求解 horizon 起点（早于该时点结束的任务不会与新排任务冲突）
     * @param horizonEnd 求解 horizon 终点（起点晚于该时点的任务不会与新排任务冲突）
     * @return 订单读取行，按交期、订单号升序
     */
    public List<OrderSourceRow> findOrdersWithExistingTasks(
            UUID tenantId, UUID sandboxId, List<UUID> excludeOrderIds,
            OffsetDateTime horizonStart, OffsetDateTime horizonEnd) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT o.id, o.order_no, o.product_id, m.code AS material_code,
                       mc.code AS product_family, o.qty, o.due_date,
                       o.priority, o.freeze_status, o.routing_id, o.status
                FROM task t
                JOIN orders o ON o.id = t.order_id
                JOIN material m ON m.id = o.product_id
                LEFT JOIN material_category mc ON mc.id = m.category_id
                WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                  AND o.tenant_id = ? AND o.deleted = false
                  AND t.end_time > ? AND coalesce(t.setup_start_time, t.start_time) < ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(sandboxId);
        args.add(tenantId);
        args.add(horizonStart);
        args.add(horizonEnd);
        if (excludeOrderIds != null && !excludeOrderIds.isEmpty()) {
            sql.append(" AND o.id NOT IN (").append(placeholders(excludeOrderIds.size())).append(')');
            args.addAll(excludeOrderIds);
        }
        sql.append(" ORDER BY o.due_date, o.order_no");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OrderSourceRow(
                rs.getObject("id", UUID.class),
                rs.getString("order_no"),
                rs.getObject("product_id", UUID.class),
                rs.getString("material_code"),
                rs.getString("product_family"),
                rs.getBigDecimal("qty"),
                rs.getObject("due_date", LocalDate.class),
                rs.getString("priority"),
                rs.getString("freeze_status"),
                rs.getObject("routing_id", UUID.class),
                rs.getString("status")), args.toArray());
    }

    /**
     * 创建处于 RUNNING 的求解任务。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param strategyId 策略标识，可空
     * @param engine 引擎编码（大写）
     * @param configJson 求解配置 JSON
     * @return 求解任务主键
     */
    public long createSolveTask(UUID tenantId, UUID sandboxId, UUID strategyId, String engine, String configJson) {
        String sql = """
                INSERT INTO solve_task (tenant_id, sandbox_id, strategy_id, engine, status, config, started_at)
                VALUES (?, ?, ?, ?, 'RUNNING', ?::jsonb, ?)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                tenantId, sandboxId, strategyId, engine, configJson, OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if (id == null) {
            throw new IllegalStateException("solve_task 插入未返回主键");
        }
        return id;
    }

    /**
     * 结束求解任务并记录评分、耗时与度量。
     *
     * @param solveTaskId 求解任务主键
     * @param status SOLVED / FAILED / TIMEOUT
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metricsJson 度量 JSON
     * @param errorMessage 失败原因，可空
     */
    public void finishSolveTask(long solveTaskId, String status, Long scoreHard, Long scoreSoft,
                                String metricsJson, String errorMessage) {
        UUID tenantId = TenantContext.getTenantId();
        String sql = """
                UPDATE solve_task
                SET status = ?, score_hard = ?, score_soft = ?, finished_at = ?, metrics = ?::jsonb,
                    error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """;
        jdbcTemplate.update(sql, status, scoreHard, scoreSoft, OffsetDateTime.now(java.time.ZoneOffset.UTC),
                metricsJson, errorMessage, solveTaskId, tenantId);
    }

    /**
     * 清理场景内参与本轮求解的旧任务，保证同一 solve_task 重跑不产生重复行。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param orderIds 参与订单主键
     * @return 被删除的任务主键，用于清理指向它们的归因
     */
    public List<Long> deleteTasks(UUID tenantId, UUID sandboxId,
                                  Map<UUID, Set<Integer>> operationIndicesByOrder) {
        if (operationIndicesByOrder.isEmpty()) {
            return List.of();
        }
        StringBuilder pairs = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(sandboxId);
        for (Map.Entry<UUID, Set<Integer>> entry : operationIndicesByOrder.entrySet()) {
            for (Integer operationIndex : entry.getValue()) {
                if (!pairs.isEmpty()) {
                    pairs.append(", ");
                }
                pairs.append("(?, ?)");
                args.add(entry.getKey());
                args.add(operationIndex);
            }
        }
        if (pairs.isEmpty()) {
            return List.of();
        }
        // 只删除本次题面内的 (订单, 工序)，窗口外已完工的 FROZEN 工序必须原样保留，
        // 它们是真实执行记录；按订单整删会把这类记录一并清掉。
        String sql = "DELETE FROM task WHERE tenant_id = ? AND sandbox_id = ?"
                + " AND (order_id, operation_index) IN (" + pairs + ") RETURNING id";
        return jdbcTemplate.queryForList(sql, Long.class, args.toArray());
    }

    /**
     * 清理指向已删除任务的归因，避免 task_ids 悬空。
     *
     * @param taskIds 已删除的任务主键
     */
    public void deleteJustificationsByTaskIds(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        UUID tenantId = TenantContext.getTenantId();
        String sql = """
                DELETE FROM constraint_justification
                 WHERE tenant_id = ?
                   AND EXISTS (
                     SELECT 1 FROM jsonb_array_elements(implicated_facts) AS fact
                      WHERE (fact ->> 'id')::bigint IN (%s)
                 )
                """.formatted(placeholders(taskIds.size()));
        Object[] args = new Object[taskIds.size() + 1];
        args[0] = tenantId;
        for (int index = 0; index < taskIds.size(); index++) {
            args[index + 1] = taskIds.get(index);
        }
        jdbcTemplate.update(sql, args);
    }

    /**
     * 写入一条排产任务。
     *
     * @param row 任务行
     * @return 生成的 BIGSERIAL 主键
     */
    public long insertTask(NewTaskRow row) {
        String sql = """
                INSERT INTO task (tenant_id, order_id, sandbox_id, operation_index, operation_template_id,
                                  resource_id, secondary_resource_ids, start_time, end_time,
                                  setup_start_time, setup_end_time, qty, status, sequence_in_resource, version,
                                  lock_type, locked_from, locked_to, locked_resource_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING id
                """;
        Long id = jdbcTemplate.queryForObject(sql, Long.class,
                row.tenantId(), row.orderId(), row.sandboxId(), row.operationIndex(),
                row.operationTemplateId(), row.resourceId(), toJson(row.secondaryResourceIds()),
                row.startTime(), row.endTime(), row.setupStartTime(), row.setupEndTime(),
                row.qty(), row.status(), row.sequenceInResource(), row.version(),
                row.lockType() == null ? "NONE" : row.lockType(),
                row.lockedFrom(), row.lockedTo(), toJson(row.lockedResourceIds()));
        if (id == null) {
            throw new IllegalStateException("task 插入未返回主键");
        }
        return id;
    }

    /**
     * 全量资源区间重叠校验：同一资源上任意两条任务的占用块
     * {@code [COALESCE(setup_start_time, start_time), end_time)} 半开区间两两比较。
     *
     * <p>{@code taskIds} 为空时校验整张 {@code task} 表（验收硬线）；
     * 非空时只返回至少一侧属于该集合的重叠对，用于区分“本次求解产生”与“历史存量”。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param taskIds 本次写入的任务主键，空表示全表
     * @return 重叠对，空表示 0 对
     */
    public List<OverlapRow> findResourceOverlaps(UUID tenantId, UUID sandboxId, List<Long> taskIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT t1.id AS first_id, t2.id AS second_id, t1.resource_id,
                       COALESCE(t1.setup_start_time, t1.start_time) AS first_start, t1.end_time AS first_end,
                       COALESCE(t2.setup_start_time, t2.start_time) AS second_start, t2.end_time AS second_end
                  FROM task t1
                  JOIN task t2 ON t1.sandbox_id = t2.sandbox_id
                              AND t1.resource_id = t2.resource_id
                              AND t1.id < t2.id
                 WHERE t1.tenant_id = ? AND t1.sandbox_id = ?
                   AND COALESCE(t1.setup_start_time, t1.start_time) < t2.end_time
                   AND COALESCE(t2.setup_start_time, t2.start_time) < t1.end_time
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(sandboxId);
        if (taskIds != null && !taskIds.isEmpty()) {
            String placeholders = placeholders(taskIds.size());
            sql.append(" AND (t1.id IN (").append(placeholders)
                    .append(") OR t2.id IN (").append(placeholders).append("))");
            args.addAll(taskIds);
            args.addAll(taskIds);
        }
        sql.append(" ORDER BY t1.resource_id, first_start, first_id");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new OverlapRow(
                        rs.getLong("first_id"),
                        rs.getLong("second_id"),
                        rs.getObject("resource_id", UUID.class),
                        rs.getObject("first_start", OffsetDateTime.class),
                        rs.getObject("first_end", OffsetDateTime.class),
                        rs.getObject("second_start", OffsetDateTime.class),
                        rs.getObject("second_end", OffsetDateTime.class)),
                args.toArray());
    }

    /**
     * 资源区间重叠对。
     *
     * @param firstTaskId 前一条任务主键（较小 id）
     * @param secondTaskId 后一条任务主键
     * @param resourceId 资源主键
     * @param firstStart 前一条占用开始（含换型）
     * @param firstEnd 前一条占用结束
     * @param secondStart 后一条占用开始（含换型）
     * @param secondEnd 后一条占用结束
     */
    public record OverlapRow(long firstTaskId, long secondTaskId, UUID resourceId,
                             OffsetDateTime firstStart, OffsetDateTime firstEnd,
                             OffsetDateTime secondStart, OffsetDateTime secondEnd) {
    }

    /**
     * 写入一条约束归因。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @param constraintCode 约束编码（软约束为 RuleCode）
     * @param constraintType HARD / SOFT
     * @param taskIds 落库后回填的任务主键
     * @param description 面向人的说明
     * @param weight 软约束权重；硬约束为空
     * @param deduction 软约束扣分；硬约束为 0
     */
    public void insertJustification(UUID tenantId, long solveTaskId, String constraintCode,
                                    String constraintType, List<Long> taskIds, String description,
                                    Integer weight, BigDecimal deduction) {
        String facts = toJson(taskIds.stream()
                .map(id -> Map.of("type", "TASK", "id", id))
                .toList());
        String sql = """
                INSERT INTO constraint_justification (tenant_id, solve_task_id, constraint_id, constraint_name,
                                                     score_impact, implicated_facts, explanation_zh,
                                                     weight, deduction)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """;
        jdbcTemplate.update(sql, tenantId, solveTaskId, constraintCode, constraintCode,
                constraintType, facts, description, weight,
                deduction == null ? BigDecimal.ZERO : deduction);
    }

    /**
     * 按场景 + 可选资源 + 时间窗查询甘特任务，命中 idx_task_sandbox_resource_start。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param resourceIds 资源主键过滤，空表示全部
     * @param from 时间窗开始（含）
     * @param to 时间窗结束（不含）
     * @return 甘特任务行，按资源编码、开始时间升序
     */
    public List<GanttTaskRow> findGanttTasks(UUID tenantId, UUID sandboxId, List<UUID> resourceIds,
                                             OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, o.order_no, t.operation_index, ot.name AS operation_name,
                       r.code AS resource_code, r.name AS resource_name, r.type AS resource_type,
                       t.secondary_resource_ids::text AS secondary_json,
                       t.start_time, t.end_time, t.setup_start_time, t.setup_end_time, t.qty,
                       t.status, t.sequence_in_resource, t.version
                FROM task t
                JOIN orders o ON o.id = t.order_id
                LEFT JOIN resource r ON r.id = t.resource_id
                LEFT JOIN operation_template ot ON ot.id = t.operation_template_id
                WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                  AND t.start_time < ? AND t.end_time > ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(sandboxId);
        args.add(to);
        args.add(from);
        if (resourceIds != null && !resourceIds.isEmpty()) {
            sql.append(" AND t.resource_id IN (").append(placeholders(resourceIds.size())).append(')');
            args.addAll(resourceIds);
        }
        sql.append(" ORDER BY t.resource_id, t.start_time, t.id");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new GanttTaskRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getInt("operation_index"),
                rs.getString("operation_name"),
                rs.getString("resource_code"),
                rs.getString("resource_name"),
                rs.getString("resource_type"),
                parseUuidArray(rs.getString("secondary_json")),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                rs.getObject("setup_start_time", OffsetDateTime.class),
                rs.getObject("setup_end_time", OffsetDateTime.class),
                rs.getBigDecimal("qty"),
                rs.getString("status"),
                (Integer) rs.getObject("sequence_in_resource"),
                rs.getInt("version")), args.toArray());
    }

    /**
     * 查询场景当前任务快照（C20：`GET /solve-tasks/{id}` 终态时为 `tasks`）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 任务快照，按订单号、工序号升序
     */
    public List<TaskSnapshotRow> findScheduledTaskRows(UUID tenantId, UUID sandboxId) {
        String sql = """
                SELECT o.order_no, COALESCE(r.code, '') AS resource_code, t.start_time, t.end_time
                FROM task t
                JOIN orders o ON o.id = t.order_id
                LEFT JOIN resource r ON r.id = t.resource_id
                WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                ORDER BY o.order_no, t.operation_index
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TaskSnapshotRow(
                rs.getString("order_no"),
                rs.getString("resource_code"),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class)), tenantId, sandboxId);
    }

    /**
     * 任务快照行。
     *
     * @param orderNo 订单号
     * @param resourceCode 资源编码
     * @param startTime 开始时间
     * @param endTime 结束时间
     */
    public record TaskSnapshotRow(String orderNo, String resourceCode,
                                  OffsetDateTime startTime, OffsetDateTime endTime) {
    }

    /**
     * 按订单泳道查询甘特数据（C29）：订单产品、交期、优先级与 [from, to) 内任务。
     *
     * <p>无任务的订单仍返回泳道（任务列为空），用于渲染交期线；任务按工序号升序。</p>
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param orderNos 订单号过滤，空表示全部
     * @param from 时间窗开始（含）
     * @param to 时间窗结束（不含）
     * @return 订单 + 任务展开行，按订单号、工序号升序（C29 稳定排序）
     */
    public List<OrderGanttRow> findOrderGanttRows(UUID tenantId, UUID sandboxId, List<String> orderNos,
                                                  OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id AS order_id, o.order_no, m.code AS material_code,
                       CASE WHEN m.tenant_id = ? AND m.deleted = false THEN m.name END AS material_name,
                       CASE WHEN m.tenant_id = ? AND m.deleted = false THEN m.spec END AS material_spec,
                       mc.code AS product_family, o.due_date, o.priority, o.freeze_status,
                       o.status AS order_status,
                       t.id AS task_id, t.operation_index, ot.name AS operation_name,
                       r.code AS resource_code, t.secondary_resource_ids::text AS secondary_json,
                       t.start_time, t.end_time, t.setup_start_time, t.setup_end_time, t.qty,
                       t.status AS task_status, t.sequence_in_resource, t.version
                FROM orders o
                JOIN material m ON m.id = o.product_id
                LEFT JOIN material_category mc ON mc.id = m.category_id
                LEFT JOIN task t ON t.order_id = o.id AND t.sandbox_id = ? AND t.deleted = false
                                    AND t.start_time < ? AND t.end_time > ?
                LEFT JOIN resource r ON r.id = t.resource_id
                LEFT JOIN operation_template ot ON ot.id = t.operation_template_id
                WHERE o.tenant_id = ? AND o.deleted = false
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(tenantId);
        args.add(sandboxId);
        args.add(to);
        args.add(from);
        args.add(tenantId);
        if (orderNos != null && !orderNos.isEmpty()) {
            sql.append(" AND o.order_no IN (").append(placeholders(orderNos.size())).append(')');
            args.addAll(orderNos);
        }
        sql.append(" ORDER BY o.order_no, t.operation_index, t.id");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OrderGanttRow(
                rs.getObject("order_id", UUID.class),
                rs.getString("order_no"),
                rs.getString("material_code"),
                rs.getString("material_name"),
                rs.getString("material_spec"),
                rs.getString("product_family"),
                rs.getObject("due_date", LocalDate.class),
                rs.getString("priority"),
                rs.getString("freeze_status"),
                rs.getString("order_status"),
                rs.getObject("task_id", Long.class),
                rs.getObject("operation_index", Integer.class),
                rs.getString("operation_name"),
                rs.getString("resource_code"),
                parseUuidArray(rs.getString("secondary_json")),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                rs.getObject("setup_start_time", OffsetDateTime.class),
                rs.getObject("setup_end_time", OffsetDateTime.class),
                rs.getBigDecimal("qty"),
                rs.getString("task_status"),
                (Integer) rs.getObject("sequence_in_resource"),
                (Integer) rs.getObject("version")), args.toArray());
    }

    /**
     * 查询租户内资源编码映射，用于把辅助资源主键翻译为编码。
     *
     * @param tenantId 租户标识
     * @return 资源主键到编码的映射
     */
    public Map<UUID, String> findResourceCodes(UUID tenantId) {
        String sql = "SELECT id, code FROM resource WHERE tenant_id = ? AND deleted = false";
        Map<UUID, String> codes = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            codes.put(rs.getObject("id", UUID.class), rs.getString("code"));
        }, tenantId);
        return codes;
    }

    /**
     * 按资源编码读取绑定的日历标识（只读 {@code resource.calendar_id}，不展开日历）。
     *
     * <p>未绑定日历的资源在映射中保留 key、值为 {@code null}，供甘特读模型区分
     * “未绑定”与“资源不存在”。</p>
     *
     * @param tenantId 租户标识
     * @return 资源编码到日历标识（值可空）
     */
    public Map<String, UUID> findResourceCalendarIds(UUID tenantId) {
        String sql = "SELECT code, calendar_id FROM resource WHERE tenant_id = ? AND deleted = false";
        Map<String, UUID> calendarIds = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            calendarIds.put(rs.getString("code"), rs.getObject("calendar_id", UUID.class));
        }, tenantId);
        return calendarIds;
    }

    /**
     * 按编码解析资源主键。
     *
     * @param tenantId 租户标识
     * @param codes 资源编码
     * @return 资源主键列表；编码不存在时跳过
     */
    public List<UUID> findResourceIdsByCodes(UUID tenantId, List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT id FROM resource WHERE tenant_id = ? AND deleted = false AND code IN ("
                + placeholders(codes.size()) + ")";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(codes);
        return jdbcTemplate.queryForList(sql, UUID.class, args.toArray());
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static List<UUID> parseUuidArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            List<UUID> ids = new ArrayList<>(values.size());
            for (String value : values) {
                ids.add(UUID.fromString(value));
            }
            return List.copyOf(ids);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    /**
     * 订单读取行。
     *
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param productId 产品主键
     * @param materialCode 产品物料编码
     * @param productFamily 产品族（物料分类编码），可空
     * @param qty 数量
     * @param dueDate 交期
     * @param priority 优先级
     * @param freezeStatus 冻结状态
     * @param routingId 指定路线主键，可空
     * @param status 订单状态
     */
    public record OrderSourceRow(UUID orderId, String orderNo, UUID productId, String materialCode,
                                 String productFamily, BigDecimal qty, LocalDate dueDate,
                                 String priority, String freezeStatus, UUID routingId, String status) {
    }

    /**
     * 窗口外已完成 FROZEN 工序行。
     *
     * @param orderId 订单主键
     * @param operationIndex 工序序号
     * @param maxEndTime 该工序的最大结束时间
     */
    public record CompletedFrozenOperationRow(
            UUID orderId, int operationIndex, OffsetDateTime maxEndTime) {
    }

    /**
     * 冻结任务读取行。
     *
     * @param taskId 任务主键
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param operationIndex 工序序号
     * @param operationTemplateId 工序模版主键
     * @param resourceId 资源主键
     * @param resourceCode 资源编码
     * @param secondaryResourceIds 辅助资源主键
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param qty 数量
     * @param status 任务状态
     * @param version 版本
     * @param lockType 锁类型
     * @param lockedFrom 锁窗开始
     * @param lockedTo 锁窗结束
     * @param lockedResourceIds 锁定资源集合
     * @param createdAt 创建时间
     */
    public record FrozenTaskRow(long taskId, UUID orderId, String orderNo, int operationIndex,
                                UUID operationTemplateId, UUID resourceId, String resourceCode,
                                List<UUID> secondaryResourceIds, OffsetDateTime setupStartTime,
                                OffsetDateTime setupEndTime, OffsetDateTime startTime, OffsetDateTime endTime,
                                BigDecimal qty, String status, int version, String lockType,
                                OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                                List<UUID> lockedResourceIds, OffsetDateTime createdAt) {
    }

    /**
     * 新任务写入行。
     *
     * @param tenantId 租户标识
     * @param orderId 订单主键
     * @param sandboxId 场景标识
     * @param operationIndex 工序序号
     * @param operationTemplateId 工序模版主键
     * @param resourceId 资源主键
     * @param secondaryResourceIds 辅助资源主键
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param qty 数量
     * @param status 任务状态
     * @param sequenceInResource 资源内顺序
     * @param version 版本
     * @param lockType 锁类型（NONE/TIME_WINDOW/RESOURCE/FROZEN）
     * @param lockedFrom 锁窗开始（闭区间，可空）
     * @param lockedTo 锁窗结束（闭区间，可空）
     * @param lockedResourceIds 锁定资源主键集合
     */
    public record NewTaskRow(UUID tenantId, UUID orderId, UUID sandboxId, int operationIndex,
                             UUID operationTemplateId, UUID resourceId, List<UUID> secondaryResourceIds,
                             OffsetDateTime startTime, OffsetDateTime endTime, OffsetDateTime setupStartTime,
                             OffsetDateTime setupEndTime, BigDecimal qty, String status,
                             int sequenceInResource, int version, String lockType,
                             OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                             List<UUID> lockedResourceIds) {

        /**
         * 兼容旧调用：无锁字段视为未锁定。
         */
        public NewTaskRow(UUID tenantId, UUID orderId, UUID sandboxId, int operationIndex,
                          UUID operationTemplateId, UUID resourceId, List<UUID> secondaryResourceIds,
                          OffsetDateTime startTime, OffsetDateTime endTime, OffsetDateTime setupStartTime,
                          OffsetDateTime setupEndTime, BigDecimal qty, String status,
                          int sequenceInResource, int version) {
            this(tenantId, orderId, sandboxId, operationIndex, operationTemplateId, resourceId,
                    secondaryResourceIds, startTime, endTime, setupStartTime, setupEndTime, qty, status,
                    sequenceInResource, version, "NONE", null, null, List.of());
        }

        /**
         * 紧凑构造器：锁字段归一化。
         */
        public NewTaskRow {
            secondaryResourceIds = secondaryResourceIds == null ? List.of() : List.copyOf(secondaryResourceIds);
            lockedResourceIds = lockedResourceIds == null ? List.of() : List.copyOf(lockedResourceIds);
            lockType = lockType == null || lockType.isBlank() ? "NONE" : lockType;
        }
    }

    /**
     * 甘特任务读取行。
     *
     * @param id 任务主键
     * @param orderNo 订单号
     * @param operationIndex 工序序号
     * @param operationName 工序名称
     * @param resourceCode 资源编码
     * @param resourceName 资源名称
     * @param resourceType 资源类型
     * @param secondaryResourceIds 辅助资源主键
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param qty 数量
     * @param status 任务状态
     * @param sequenceInResource 资源内顺序
     * @param version 版本
     */
    public record GanttTaskRow(long id, String orderNo, int operationIndex, String operationName,
                               String resourceCode, String resourceName, String resourceType,
                               List<UUID> secondaryResourceIds, OffsetDateTime startTime,
                               OffsetDateTime endTime, OffsetDateTime setupStartTime,
                               OffsetDateTime setupEndTime, BigDecimal qty, String status,
                               Integer sequenceInResource, int version) {
    }

    /**
     * 订单甘特展开行：订单 × 任务；任务列为空表示该订单在窗口内无任务。
     *
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param materialCode 产品物料编码
     * @param materialName 产品物料名称（C50），物料缺失/已软删除时为空
     * @param materialSpec 产品物料规格（C50），物料缺失/已软删除或规格为空时为空
     * @param productFamily 产品族（物料分类编码），可空
     * @param dueDate 交期日
     * @param priority 优先级
     * @param freezeStatus 冻结状态
     * @param orderStatus 订单状态
     * @param taskId 任务主键，可空
     * @param operationIndex 工序号，可空
     * @param operationName 工序名称，可空
     * @param resourceCode 主资源编码，可空
     * @param secondaryResourceIds 辅助资源主键
     * @param startTime 开始时间，可空
     * @param endTime 结束时间，可空
     * @param setupStartTime 准备开始时间，可空
     * @param setupEndTime 准备结束时间，可空
     * @param qty 任务数量，可空
     * @param taskStatus 任务状态，可空
     * @param sequenceInResource 资源内序号，可空
     * @param version 乐观锁版本，可空
     */
    public record OrderGanttRow(UUID orderId, String orderNo, String materialCode,
                                String materialName, String materialSpec,
                                String productFamily, LocalDate dueDate, String priority,
                                String freezeStatus, String orderStatus, Long taskId,
                                Integer operationIndex, String operationName, String resourceCode,
                                List<UUID> secondaryResourceIds, OffsetDateTime startTime,
                                OffsetDateTime endTime, OffsetDateTime setupStartTime,
                                OffsetDateTime setupEndTime, BigDecimal qty, String taskStatus,
                                Integer sequenceInResource, Integer version) {
    }
}
