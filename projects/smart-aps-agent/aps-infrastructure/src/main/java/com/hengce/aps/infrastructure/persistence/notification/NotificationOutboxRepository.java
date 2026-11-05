package com.hengce.aps.infrastructure.persistence.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计划下发 outbox 持久层（C33 / V11）。
 *
 * <p>只做落库与状态流转，不做真实发送。唯一索引 {@code (tenant_id, dispatch_id, channel, address)}
 * 由 V11 保证，写入使用 {@code ON CONFLICT DO NOTHING}，重复请求与重试都不会产生重复行。</p>
 */
@Repository
public class NotificationOutboxRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, dispatch_id, plan_id, channel, address, message,
                   status, attempts, next_attempt_at, last_error, created_at
              FROM notification_outbox
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public NotificationOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一个目标；已存在同一 {@code (dispatch_id, channel, address)} 时静默跳过。
     *
     * @param tenantId 租户标识
     * @param dispatchId 下发批次标识
     * @param planId 计划版本主键
     * @param channel 通道
     * @param address 地址
     * @param message 附加说明，可为空
     * @param queuedAt 入队时间
     * @return 实际插入行数（0 表示已存在）
     */
    public int insertTarget(UUID tenantId, UUID dispatchId, UUID planId, String channel,
                           String address, String message, OffsetDateTime queuedAt) {
        return jdbcTemplate.update("""
                INSERT INTO notification_outbox (tenant_id, dispatch_id, plan_id, channel, address,
                                                 message, status, attempts, next_attempt_at,
                                                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', 0, ?, ?, ?)
                ON CONFLICT (tenant_id, dispatch_id, channel, address) DO NOTHING
                """, tenantId, dispatchId, planId, channel, address, message, queuedAt, queuedAt, queuedAt);
    }

    /**
     * 统计某一 dispatch 已落库的目标数。
     *
     * @param tenantId 租户标识
     * @param dispatchId 下发批次标识
     * @return 目标数
     */
    public long countByDispatch(UUID tenantId, UUID dispatchId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM notification_outbox
                 WHERE tenant_id = ? AND dispatch_id = ?
                """, Long.class, tenantId, dispatchId);
        return count == null ? 0L : count;
    }

    /**
     * 查询某一 dispatch 的最早入队时间，供重复请求返回首次时间。
     *
     * @param tenantId 租户标识
     * @param dispatchId 下发批次标识
     * @return 最早入队时间
     */
    public Optional<OffsetDateTime> findQueuedAt(UUID tenantId, UUID dispatchId) {
        List<OffsetDateTime> rows = jdbcTemplate.query("""
                SELECT MIN(created_at) AS queued_at FROM notification_outbox
                 WHERE tenant_id = ? AND dispatch_id = ?
                """, (rs, rowNum) -> rs.getObject("queued_at", OffsetDateTime.class), tenantId, dispatchId);
        return rows.stream().findFirst().filter(java.util.Objects::nonNull);
    }

    /**
     * 统计计划下的 dispatch 批次数。
     *
     * @param tenantId 租户标识
     * @param planId 计划版本主键
     * @return 批次数
     */
    public long countDispatches(UUID tenantId, UUID planId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT dispatch_id) FROM notification_outbox
                 WHERE tenant_id = ? AND plan_id = ?
                """, Long.class, tenantId, planId);
        return count == null ? 0L : count;
    }

    /**
     * 分页查询计划下的 dispatch 聚合状态。
     *
     * @param tenantId 租户标识
     * @param planId 计划版本主键
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 聚合行
     */
    public List<DispatchAggregateRow> pageDispatches(UUID tenantId, UUID planId, int offset, int limit) {
        return jdbcTemplate.query("""
                SELECT dispatch_id, MIN(created_at) AS created_at,
                       COUNT(*) AS target_count,
                       COUNT(*) FILTER (WHERE status = 'SENT') AS sent_count,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_count,
                       COUNT(*) FILTER (WHERE status = 'QUEUED') AS queued_count
                  FROM notification_outbox
                 WHERE tenant_id = ? AND plan_id = ?
                 GROUP BY dispatch_id
                 ORDER BY created_at DESC, dispatch_id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new DispatchAggregateRow(
                rs.getObject("dispatch_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("target_count"),
                rs.getInt("sent_count"),
                rs.getInt("failed_count"),
                rs.getInt("queued_count")),
                tenantId, planId, limit, offset);
    }

    /**
     * 读取到了重试时间的 QUEUED 行，带 {@code FOR UPDATE SKIP LOCKED} 防止多实例重复领取。
     *
     * @param tenantId 租户标识
     * @param now 当前时间
     * @param limit 本批最多处理条数
     * @return outbox 行
     */
    public List<OutboxRow> listDue(UUID tenantId, OffsetDateTime now, int limit) {
        return jdbcTemplate.query(SELECT_COLUMNS
                        + " WHERE tenant_id = ? AND status = 'QUEUED' AND next_attempt_at <= ?"
                        + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                ROW_MAPPER, tenantId, now, limit);
    }

    /**
     * 标记发送成功。
     *
     * @param tenantId 租户标识
     * @param id outbox 主键
     * @param attempts 本次尝试次数
     * @return 实际更新行数
     */
    public int markSent(UUID tenantId, long id, int attempts) {
        return jdbcTemplate.update("""
                UPDATE notification_outbox
                   SET status = 'SENT', attempts = ?, last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND status = 'QUEUED'
                """, attempts, tenantId, id);
    }

    /**
     * 标记本次失败、等待重试。
     *
     * @param tenantId 租户标识
     * @param id outbox 主键
     * @param attempts 已尝试次数
     * @param nextAttemptAt 下次尝试时间（退避 30s）
     * @param lastError 最近错误
     * @return 实际更新行数
     */
    public int markRetry(UUID tenantId, long id, int attempts, OffsetDateTime nextAttemptAt, String lastError) {
        return jdbcTemplate.update("""
                UPDATE notification_outbox
                   SET status = 'QUEUED', attempts = ?, next_attempt_at = ?, last_error = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND status = 'QUEUED'
                """, attempts, nextAttemptAt, lastError, tenantId, id);
    }

    /**
     * 标记终态失败（尝试次数耗尽）。
     *
     * @param tenantId 租户标识
     * @param id outbox 主键
     * @param attempts 已尝试次数
     * @param lastError 最近错误
     * @return 实际更新行数
     */
    public int markFailed(UUID tenantId, long id, int attempts, String lastError) {
        return jdbcTemplate.update("""
                UPDATE notification_outbox
                   SET status = 'FAILED', attempts = ?, last_error = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ? AND status = 'QUEUED'
                """, attempts, lastError, tenantId, id);
    }

    private static final RowMapper<OutboxRow> ROW_MAPPER = (rs, rowNum) -> new OutboxRow(
            rs.getLong("id"),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("dispatch_id", UUID.class),
            rs.getObject("plan_id", UUID.class),
            rs.getString("channel"),
            rs.getString("address"),
            rs.getString("message"),
            rs.getString("status"),
            rs.getInt("attempts"),
            rs.getObject("next_attempt_at", OffsetDateTime.class),
            rs.getString("last_error"),
            rs.getObject("created_at", OffsetDateTime.class));

    /**
     * outbox 行。
     *
     * @param id 主键
     * @param tenantId 租户标识
     * @param dispatchId 下发批次标识
     * @param planId 计划版本主键
     * @param channel 通道
     * @param address 地址
     * @param message 附加说明
     * @param status QUEUED / SENT / FAILED
     * @param attempts 已尝试次数
     * @param nextAttemptAt 下次尝试时间
     * @param lastError 最近错误
     * @param createdAt 创建时间
     */
    public record OutboxRow(
            long id,
            UUID tenantId,
            UUID dispatchId,
            UUID planId,
            String channel,
            String address,
            String message,
            String status,
            int attempts,
            OffsetDateTime nextAttemptAt,
            String lastError,
            OffsetDateTime createdAt) {
    }

    /**
     * dispatch 聚合行。
     *
     * @param dispatchId 下发批次标识
     * @param createdAt 最早入队时间
     * @param targetCount 目标数
     * @param sentCount 成功数
     * @param failedCount 失败数
     * @param queuedCount 待发送数
     */
    public record DispatchAggregateRow(
            UUID dispatchId,
            OffsetDateTime createdAt,
            int targetCount,
            int sentCount,
            int failedCount,
            int queuedCount) {
    }
}
