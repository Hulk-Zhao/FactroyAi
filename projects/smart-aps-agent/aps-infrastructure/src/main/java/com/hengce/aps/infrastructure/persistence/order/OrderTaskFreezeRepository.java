package com.hengce.aps.infrastructure.persistence.order;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 订单释放/开工时的任务自动冻结持久层。
 *
 * <p>语义（C23）：订单进入 {@code RELEASED}/{@code IN_PROGRESS} 后，其下仍为
 * {@code PLANNED} 的任务自动置为 {@code FROZEN}，使后续重排跳过或保留这些任务；
 * 已 {@code IN_PROGRESS}/{@code COMPLETED} 的任务不受影响。</p>
 *
 * <p>{@code task} 为 BIGSERIAL 高频表且不由 MyBatis 实体承载；本类使用 {@link JdbcTemplate}
 * 并显式携带 {@code tenant_id}，不改动 {@code task} 既有列语义。</p>
 */
@Repository
public class OrderTaskFreezeRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public OrderTaskFreezeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 将订单下所有 PLANNED 任务置为 FROZEN。
     *
     * <p>覆盖该订单在所有场景中的 PLANNED 任务：M2 仅使用默认 BASELINE 场景，
     * 但释放动作按订单全局生效，避免 WHAT_IF 场景中的同订单任务被后续重排覆盖。</p>
     *
     * @param tenantId 租户标识
     * @param orderId 订单主键
     * @return 被自动冻结的任务行数
     */
    public int freezePlannedTasks(UUID tenantId, UUID orderId) {
        String sql = """
                UPDATE task
                   SET status = 'FROZEN', lock_type = 'FROZEN', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND order_id = ? AND deleted = false AND status = 'PLANNED'
                """;
        return jdbcTemplate.update(sql, tenantId, orderId);
    }
}
