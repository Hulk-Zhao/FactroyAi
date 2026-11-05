package com.hengce.aps.infrastructure.persistence.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 约束归因持久层（M2-SE-05）：读取归因并重放 {@code implicated_facts.task_ids}。
 *
 * <p>写入仍由 {@code ScheduleResultWriter} 在求解结果事务内完成；本类只负责归因查询、
 * {@code task_ids} 解析与空归因的补齐，避免与结果写入事务交叉。</p>
 */
@Repository
public class ConstraintJustificationRepository {

    /**
     * JSON 解析器。
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
    public ConstraintJustificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按求解任务读取归因，排序遵循契约 C19 §24.1.1：
     * {@code deduction DESC, constraintCode ASC}（即 {@code deduction DESC, constraint_id ASC}）。
     *
     * <p>排序只在 SQL 中实现，服务层与接口层不得再次排序，避免两处规则漂移。</p>
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 归因行
     */
    public List<JustificationRow> findBySolveTaskId(UUID tenantId, long solveTaskId) {
        String sql = """
                SELECT id, solve_task_id, constraint_id, constraint_name, score_impact,
                       implicated_facts::text AS facts_json, explanation_zh,
                       weight, deduction, created_at
                  FROM constraint_justification
                 WHERE tenant_id = ? AND solve_task_id = ? AND deleted = false
                 ORDER BY deduction DESC, constraint_id ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new JustificationRow(
                rs.getObject("id", UUID.class),
                rs.getLong("solve_task_id"),
                rs.getString("constraint_id"),
                rs.getString("constraint_name"),
                rs.getString("score_impact"),
                parseTaskIds(rs.getString("facts_json")),
                rs.getString("explanation_zh"),
                rs.getObject("weight", Integer.class),
                rs.getBigDecimal("deduction"),
                rs.getObject("created_at", OffsetDateTime.class)), tenantId, solveTaskId);
    }

    /**
     * 归因按 solve_task 持久化，契约只给 taskId：本方法用
     * {@code implicated_facts} 反查“哪些求解任务的归因包含该任务”。
     *
     * @param tenantId 租户标识
     * @param taskId 任务主键
     * @return 归因行；可能来自多个 solve_task，由调用方判定歧义
     */
    public List<JustificationRow> findByAffectedTaskId(UUID tenantId, long taskId) {
        String sql = """
                SELECT id, solve_task_id, constraint_id, constraint_name, score_impact,
                       implicated_facts::text AS facts_json, explanation_zh,
                       weight, deduction, created_at
                  FROM constraint_justification
                 WHERE tenant_id = ? AND deleted = false
                   AND (implicated_facts @> ?::jsonb OR implicated_facts @> ?::jsonb)
                 ORDER BY deduction DESC, constraint_id ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new JustificationRow(
                rs.getObject("id", UUID.class),
                rs.getLong("solve_task_id"),
                rs.getString("constraint_id"),
                rs.getString("constraint_name"),
                rs.getString("score_impact"),
                parseTaskIds(rs.getString("facts_json")),
                rs.getString("explanation_zh"),
                rs.getObject("weight", Integer.class),
                rs.getBigDecimal("deduction"),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, "[{\"id\":" + taskId + "}]", "[{\"taskId\":" + taskId + "}]");
    }

    /**
     * 归因是否已带 task_ids。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 至少一条归因带非空 task_ids 时返回 {@code true}
     */
    public boolean hasTaskIds(UUID tenantId, long solveTaskId) {
        return findBySolveTaskId(tenantId, solveTaskId).stream()
                .anyMatch(row -> !row.taskIds().isEmpty());
    }

    /**
     * 重放修复：仅当归因的 {@code implicated_facts} 为空数组时，补齐给定 task_ids。
     *
     * @param tenantId 租户标识
     * @param justificationId 归因主键
     * @param taskIds 待补齐的任务主键
     * @return 受影响行数
     */
    public int backfillTaskIds(UUID tenantId, UUID justificationId, List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        String facts = toFactsJson(taskIds);
        String sql = """
                UPDATE constraint_justification
                   SET implicated_facts = ?::jsonb, updated_at = now()
                 WHERE tenant_id = ? AND id = ? AND deleted = false
                   AND implicated_facts = '[]'::jsonb
                """;
        return jdbcTemplate.update(sql, facts, tenantId, justificationId);
    }

    private static String toFactsJson(List<Long> taskIds) {
        List<java.util.Map<String, Object>> facts = new ArrayList<>(taskIds.size());
        for (Long taskId : taskIds) {
            facts.add(java.util.Map.of("type", "TASK", "id", taskId));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(facts);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("归因 facts 序列化失败", exception);
        }
    }

    private static List<Long> parseTaskIds(String factsJson) {
        if (factsJson == null || factsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(factsJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<Long> taskIds = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode idNode = node.get("id");
                if (idNode == null || idNode.isNull()) {
                    idNode = node.get("taskId");
                }
                if (idNode != null && idNode.canConvertToLong()) {
                    taskIds.add(idNode.asLong());
                } else if (idNode != null && idNode.isTextual()) {
                    try {
                        taskIds.add(Long.parseLong(idNode.asText()));
                    } catch (NumberFormatException ignored) {
                        // 非任务型事实（如 RESOURCE）忽略。
                    }
                }
            }
            return List.copyOf(taskIds);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("归因 facts 解析失败", exception);
        }
    }

    /**
     * 归因行。
     *
     * @param id 归因主键
     * @param solveTaskId 求解任务主键
     * @param constraintId 约束编码
     * @param constraintName 约束名称，可空
     * @param scoreImpact 评分影响类型：HARD/SOFT，可空
     * @param taskIds 受影响任务主键
     * @param description 面向人的说明，可空
     * @param weight 软约束权重，可空
     * @param deduction 软约束扣分，硬约束为 0
     * @param createdAt 创建时间
     */
    public record JustificationRow(
            UUID id,
            long solveTaskId,
            String constraintId,
            String constraintName,
            String scoreImpact,
            List<Long> taskIds,
            String description,
            Integer weight,
            BigDecimal deduction,
            OffsetDateTime createdAt
    ) {
    }
}
