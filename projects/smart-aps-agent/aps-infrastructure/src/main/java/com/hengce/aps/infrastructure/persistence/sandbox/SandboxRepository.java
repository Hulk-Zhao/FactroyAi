package com.hengce.aps.infrastructure.persistence.sandbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 场景（sandbox）生命周期持久层（C32 / V1 既有表）。
 *
 * <p>V1 已建 {@code sandbox} 表与「每租户唯一 BASELINE」部分唯一索引 {@code uq_sandbox_baseline}，
 * 因此本类不新增任何迁移。所有语句显式携带 {@code tenant_id}，与 Web 层租户上下文一致。</p>
 */
@Repository
public class SandboxRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, name, type, parent_sandbox_id, snapshot::text AS snapshot,
                   expires_at, version, created_at
              FROM sandbox
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public SandboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按主键查询未删除场景。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 场景行
     */
    public Optional<SandboxRow> find(UUID tenantId, UUID sandboxId) {
        List<SandboxRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ? AND deleted = false",
                ROW_MAPPER, tenantId, sandboxId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键加行锁查询场景，供 apply 在事务内串行化同一场景的并发应用。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 场景行
     */
    public Optional<SandboxRow> findForUpdate(UUID tenantId, UUID sandboxId) {
        List<SandboxRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND id = ? AND deleted = false FOR UPDATE",
                ROW_MAPPER, tenantId, sandboxId);
        return rows.stream().findFirst();
    }

    /**
     * 查询租户当前 BASELINE 场景。
     *
     * @param tenantId 租户标识
     * @return BASELINE 场景
     */
    public Optional<SandboxRow> findBaseline(UUID tenantId) {
        List<SandboxRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND type = 'BASELINE' AND deleted = false",
                ROW_MAPPER, tenantId);
        return rows.stream().findFirst();
    }

    /**
     * 统计场景数；{@code type} 为空时按契约排除 {@code ARCHIVED}。
     *
     * @param tenantId 租户标识
     * @param type 类型过滤，可空
     * @return 总数
     */
    public long count(UUID tenantId, String type) {
        String sql = type == null
                ? "SELECT COUNT(1) FROM sandbox WHERE tenant_id = ? AND deleted = false AND type <> 'ARCHIVED'"
                : "SELECT COUNT(1) FROM sandbox WHERE tenant_id = ? AND deleted = false AND type = ?";
        Long count = type == null
                ? jdbcTemplate.queryForObject(sql, Long.class, tenantId)
                : jdbcTemplate.queryForObject(sql, Long.class, tenantId, type);
        return count == null ? 0L : count;
    }

    /**
     * 分页查询场景；排序固定 {@code created_at DESC, id DESC}（契约只允许 createdAt）。
     *
     * @param tenantId 租户标识
     * @param type 类型过滤，可空（空时排除 ARCHIVED）
     * @param ascending 是否按 createdAt 升序
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 场景行
     */
    public List<SandboxRow> page(UUID tenantId, String type, boolean ascending, int offset, int limit) {
        String direction = ascending ? "ASC" : "DESC";
        String where = type == null
                ? " AND type <> 'ARCHIVED'"
                : " AND type = ?";
        String sql = SELECT_COLUMNS + " WHERE tenant_id = ? AND deleted = false" + where
                + " ORDER BY created_at " + direction + ", id " + direction + " LIMIT ? OFFSET ?";
        return type == null
                ? jdbcTemplate.query(sql, ROW_MAPPER, tenantId, limit, offset)
                : jdbcTemplate.query(sql, ROW_MAPPER, tenantId, type, limit, offset);
    }

    /**
     * 新建 WHAT_IF 场景，深拷贝父场景 snapshot。
     *
     * @param tenantId 租户标识
     * @param name 场景名称
     * @param parentSandboxId 父场景
     * @param snapshotJson 父场景 snapshot 文本
     * @param expiresAt 过期时间，可为空
     * @param operator 操作人，可为空
     * @return 新场景主键
     */
    public UUID insertWhatIf(UUID tenantId, String name, UUID parentSandboxId, String snapshotJson,
                             OffsetDateTime expiresAt, UUID operator) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sandbox (id, tenant_id, name, type, parent_sandbox_id, snapshot,
                                     expires_at, version, created_by, updated_by)
                VALUES (?, ?, ?, 'WHAT_IF', ?, ?::jsonb, ?, 1, ?, ?)
                """, id, tenantId, name, parentSandboxId, snapshotJson, expiresAt, operator, operator);
        return id;
    }

    /**
     * 旧 BASELINE 归档为 ARCHIVED（不删除任务、不改历史 plan_version.sandbox_id）。
     *
     * @param tenantId 租户标识
     * @param sandboxId 旧 BASELINE 主键
     * @param operator 操作人，可为空
     * @return 实际更新行数
     */
    public int archiveBaseline(UUID tenantId, UUID sandboxId, UUID operator) {
        return jdbcTemplate.update("""
                UPDATE sandbox SET type = 'ARCHIVED', version = version + 1,
                                   updated_by = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND type = 'BASELINE' AND deleted = false
                """, operator, tenantId, sandboxId);
    }

    /**
     * 目标 WHAT_IF 提升为 BASELINE，沿用同一 id 并 {@code version + 1}。
     *
     * @param tenantId 租户标识
     * @param sandboxId 目标场景主键
     * @param operator 操作人，可为空
     * @return 实际更新行数
     */
    public int promoteToBaseline(UUID tenantId, UUID sandboxId, UUID operator) {
        return jdbcTemplate.update("""
                UPDATE sandbox SET type = 'BASELINE', version = version + 1,
                                   updated_by = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND type = 'WHAT_IF' AND deleted = false
                """, operator, tenantId, sandboxId);
    }

    /**
     * 统计场景内未删除任务数，供 apply 的「无任务拒绝」判定。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 任务数
     */
    public long countTasks(UUID tenantId, UUID sandboxId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM task
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                """, Long.class, tenantId, sandboxId);
        return count == null ? 0L : count;
    }

    /**
     * 读取场景内任务，供 compare 以 {@code (orderNo, operationIndex)} 为业务键匹配两侧。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @return 对比用任务行
     */
    public List<CompareTaskRow> listCompareTasks(UUID tenantId, UUID sandboxId) {
        return jdbcTemplate.query("""
                SELECT t.id, o.order_no, t.operation_index, t.start_time, t.end_time,
                       r.code AS resource_code, o.due_date,
                       t.setup_start_time
                  FROM task t
                  LEFT JOIN orders o ON o.id = t.order_id AND o.deleted = false
                  LEFT JOIN resource r ON r.id = t.resource_id AND r.deleted = false
                 WHERE t.tenant_id = ? AND t.sandbox_id = ? AND t.deleted = false
                 ORDER BY o.order_no, t.operation_index, t.id
                """, (rs, rowNum) -> new CompareTaskRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getInt("operation_index"),
                rs.getObject("start_time", OffsetDateTime.class),
                rs.getObject("end_time", OffsetDateTime.class),
                rs.getString("resource_code"),
                rs.getObject("due_date", java.time.LocalDate.class),
                rs.getObject("setup_start_time", OffsetDateTime.class) != null),
                tenantId, sandboxId);
    }

    private static final RowMapper<SandboxRow> ROW_MAPPER = (rs, rowNum) -> new SandboxRow(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("name"),
            rs.getString("type"),
            rs.getObject("parent_sandbox_id", UUID.class),
            rs.getString("snapshot"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getInt("version"),
            rs.getObject("created_at", OffsetDateTime.class));

    /**
     * 场景行。
     *
     * @param id 主键
     * @param tenantId 租户标识
     * @param name 名称
     * @param type BASELINE / WHAT_IF / ARCHIVED
     * @param parentSandboxId 父场景
     * @param snapshotJson 快照 JSON 文本
     * @param expiresAt 过期时间
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     */
    public record SandboxRow(
            UUID id,
            UUID tenantId,
            String name,
            String type,
            UUID parentSandboxId,
            String snapshotJson,
            OffsetDateTime expiresAt,
            int version,
            OffsetDateTime createdAt) {
    }

    /**
     * compare 用任务行。
     *
     * @param taskId 任务主键
     * @param orderNo 订单号
     * @param operationIndex 工序号
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param resourceCode 主资源编码
     * @param dueDate 订单交期
     * @param hasSetup 是否含换型时段
     */
    public record CompareTaskRow(
            long taskId,
            String orderNo,
            int operationIndex,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String resourceCode,
            java.time.LocalDate dueDate,
            boolean hasSetup) {
    }
}
