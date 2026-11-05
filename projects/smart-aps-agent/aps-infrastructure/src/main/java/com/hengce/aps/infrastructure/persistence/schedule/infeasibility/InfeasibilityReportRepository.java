package com.hengce.aps.infrastructure.persistence.schedule.infeasibility;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * C51 不可行证据持久层（B2）。
 *
 * <p>{@code (tenant_id, solve_task_id)} 唯一，重复终态与重试走 upsert，始终只保留一行；
 * 写入内容由服务层序列化为 JSON，本层不依赖任何业务 DTO。</p>
 */
@Repository
public class InfeasibilityReportRepository {

    /**
     * 读取列。
     */
    private static final String SELECT_COLUMNS = """
            SELECT solve_task_id, verdict, generated_at, candidate_seed, truncated, llm_status,
                   narrative, report_json::text AS report_json,
                   repair_candidates_json::text AS repair_candidates_json, created_at
            FROM infeasibility_report
            """;

    /**
     * 行映射。
     */
    private static final RowMapper<InfeasibilityReportRow> ROW_MAPPER = (rs, rowNum) ->
            new InfeasibilityReportRow(
                    rs.getLong("solve_task_id"),
                    rs.getString("verdict"),
                    rs.getObject("generated_at", OffsetDateTime.class),
                    rs.getObject("candidate_seed", Long.class),
                    rs.getBoolean("truncated"),
                    rs.getString("llm_status"),
                    rs.getString("narrative"),
                    rs.getString("report_json"),
                    rs.getString("repair_candidates_json"),
                    rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public InfeasibilityReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按租户与任务查询已落库报告。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 报告行；不存在时为空
     */
    public Optional<InfeasibilityReportRow> find(UUID tenantId, long solveTaskId) {
        List<InfeasibilityReportRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND solve_task_id = ?",
                ROW_MAPPER, tenantId, solveTaskId);
        return rows.stream().findFirst();
    }

    /**
     * 按 {@code (tenant_id, solve_task_id)} 幂等写入。
     *
     * <p>{@code generated_at} 原样写入，不做任何兜底；{@code NO_EVIDENCE} 行为 null。</p>
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @param verdict C51 结论
     * @param generatedAt 证据生成时间，可空
     * @param candidateSeed 复现 seed，可空
     * @param truncated 是否截断
     * @param llmStatus LLM 状态
     * @param narrative 说明，可空
     * @param reportJson C51 完整报告 JSON
     * @param repairCandidatesJson 修复候选 JSON 数组
     * @return 受影响行数（插入或更新均为 1）
     */
    public int upsert(UUID tenantId, long solveTaskId, String verdict, OffsetDateTime generatedAt,
                      Long candidateSeed, boolean truncated, String llmStatus, String narrative,
                      String reportJson, String repairCandidatesJson) {
        String sql = """
                INSERT INTO infeasibility_report (tenant_id, solve_task_id, verdict, generated_at,
                        candidate_seed, truncated, llm_status, narrative, report_json,
                        repair_candidates_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (tenant_id, solve_task_id) DO UPDATE
                   SET verdict = EXCLUDED.verdict,
                       generated_at = EXCLUDED.generated_at,
                       candidate_seed = EXCLUDED.candidate_seed,
                       truncated = EXCLUDED.truncated,
                       llm_status = EXCLUDED.llm_status,
                       narrative = EXCLUDED.narrative,
                       report_json = EXCLUDED.report_json,
                       repair_candidates_json = EXCLUDED.repair_candidates_json
                """;
        return jdbcTemplate.update(sql, tenantId, solveTaskId, verdict, generatedAt, candidateSeed,
                truncated, llmStatus, narrative, reportJson, repairCandidatesJson);
    }

    /**
     * 已落库报告行。
     *
     * @param solveTaskId 求解任务主键
     * @param verdict C51 结论
     * @param generatedAt 证据生成时间，可空
     * @param candidateSeed 复现 seed，可空
     * @param truncated 是否截断
     * @param llmStatus LLM 状态
     * @param narrative 说明，可空
     * @param reportJson C51 完整报告 JSON
     * @param repairCandidatesJson 修复候选 JSON
     * @param createdAt 首次落库时间
     */
    public record InfeasibilityReportRow(
            long solveTaskId,
            String verdict,
            OffsetDateTime generatedAt,
            Long candidateSeed,
            boolean truncated,
            String llmStatus,
            String narrative,
            String reportJson,
            String repairCandidatesJson,
            OffsetDateTime createdAt
    ) {
    }
}
