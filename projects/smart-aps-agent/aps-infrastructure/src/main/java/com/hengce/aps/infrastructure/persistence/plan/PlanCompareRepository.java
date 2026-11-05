package com.hengce.aps.infrastructure.persistence.plan;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 多策略择优（C35 §25.7.2）只读持久层。
 *
 * <p>计划版本与求解任务之间没有外键列，按“同租户 + 同场景 + 发布时刻之前最后一次 SOLVED”
 * 定位产生该版本快照的求解记录；排序固定 {@code finished_at DESC, id DESC}，保证同一输入
 * 多次调用命中同一条记录。</p>
 */
@Repository
public class PlanCompareRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造只读持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public PlanCompareRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询计划版本发布时刻（含）之前最后一次成功的求解记录。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param publishedAt 计划发布时间，空时按当前时刻
     * @return 求解评分与指标
     */
    public Optional<SolveSnapshot> findLatestSolvedBefore(UUID tenantId, UUID sandboxId,
                                                          OffsetDateTime publishedAt) {
        OffsetDateTime upperBound = publishedAt == null
                ? OffsetDateTime.now(ZoneOffset.UTC) : publishedAt;
        List<SolveSnapshot> rows = jdbcTemplate.query("""
                SELECT score_hard, score_soft, metrics::text AS metrics_json
                  FROM solve_task
                 WHERE tenant_id = ? AND sandbox_id = ? AND deleted = false
                   AND status = 'SOLVED' AND finished_at IS NOT NULL AND finished_at <= ?
                 ORDER BY finished_at DESC, id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new SolveSnapshot(
                        rs.getObject("score_hard", Long.class),
                        rs.getObject("score_soft", Long.class),
                        rs.getString("metrics_json")),
                tenantId, sandboxId, upperBound);
        return rows.stream().findFirst();
    }

    /**
     * 求解记录中的评分与指标。
     *
     * @param scoreHard 硬分
     * @param scoreSoft 软分
     * @param metricsJson solve_task.metrics 的 JSON 原文
     */
    public record SolveSnapshot(Long scoreHard, Long scoreSoft, String metricsJson) {
    }
}
