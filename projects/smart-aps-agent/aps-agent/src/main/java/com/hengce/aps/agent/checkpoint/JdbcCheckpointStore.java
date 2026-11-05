package com.hengce.aps.agent.checkpoint;

import com.hengce.aps.agent.llm.LlmDegradationReason;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 基于 PostgreSQL 的检查点存储（V13：{@code agent_run} + {@code agent_checkpoint}）。
 *
 * <p>选型理由：断点必须跨进程、跨重启存活，且与 Prompt 版本、租户同库；
 * 状态快照以 JSONB 保存，避免为每个 Agent 业务单独建中间表。
 * {@code agent_checkpoint} 以 {@code (run_id, step_no)} 唯一约束做幂等 upsert。</p>
 */
@Component
public class JdbcCheckpointStore implements CheckpointStore {

    private static final RowMapper<AgentRunRecord> RUN_MAPPER = (resultSet, rowNum) -> new AgentRunRecord(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("tenant_id", UUID.class),
            AgentRunStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("prompt_key"),
            resultSet.getObject("prompt_version", Integer.class),
            resultSet.getString("provider"),
            toReason(resultSet.getString("degradation_reason")),
            resultSet.getInt("last_completed_step"),
            resultSet.getString("variables"),
            resultSet.getString("error_message"));

    private static final RowMapper<AgentCheckpointRecord> CHECKPOINT_MAPPER = (resultSet, rowNum) ->
            new AgentCheckpointRecord(
                    resultSet.getObject("run_id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getInt("step_no"),
                    resultSet.getString("step_name"),
                    resultSet.getString("state"),
                    resultSet.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造存储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentRunRecord createRun(UUID tenantId, String promptKey, String variablesJson) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agent_run (id, tenant_id, status, prompt_key, variables) "
                        + "VALUES (?, ?, 'RUNNING', ?, CAST(? AS jsonb))",
                runId, tenantId, promptKey, variablesJson == null ? "{}" : variablesJson);
        return findRun(runId)
                .orElseThrow(() -> new IllegalStateException("运行记录写入后不可见: " + runId));
    }

    @Override
    public Optional<AgentRunRecord> findRun(UUID runId) {
        List<AgentRunRecord> rows = jdbcTemplate.query(
                "SELECT id, tenant_id, status, prompt_key, prompt_version, provider, degradation_reason, "
                        + "last_completed_step, variables, error_message FROM agent_run WHERE id = ?",
                RUN_MAPPER, runId);
        return rows.stream().findFirst();
    }

    @Override
    public void updateRun(UUID runId, AgentRunStatus status, Integer promptVersion, String provider,
                          LlmDegradationReason degradationReason, int lastCompletedStep, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE agent_run SET status = ?, prompt_version = ?, provider = ?, degradation_reason = ?, "
                        + "last_completed_step = ?, error_message = ?, updated_at = now() WHERE id = ?",
                status.name(), promptVersion, provider,
                degradationReason == null ? null : degradationReason.name(),
                lastCompletedStep, errorMessage, runId);
    }

    @Override
    public void saveCheckpoint(UUID runId, UUID tenantId, int stepNo, String stepName, String stateJson) {
        jdbcTemplate.update(
                "INSERT INTO agent_checkpoint (run_id, tenant_id, step_no, step_name, state) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb)) "
                        + "ON CONFLICT (run_id, step_no) DO UPDATE "
                        + "SET step_name = EXCLUDED.step_name, state = EXCLUDED.state, created_at = now()",
                runId, tenantId, stepNo, stepName, stateJson);
    }

    @Override
    public Optional<AgentCheckpointRecord> latestCheckpoint(UUID runId) {
        List<AgentCheckpointRecord> rows = jdbcTemplate.query(
                "SELECT run_id, tenant_id, step_no, step_name, state, created_at "
                        + "FROM agent_checkpoint WHERE run_id = ? ORDER BY step_no DESC LIMIT 1",
                CHECKPOINT_MAPPER, runId);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentCheckpointRecord> checkpoints(UUID runId) {
        return jdbcTemplate.query(
                "SELECT run_id, tenant_id, step_no, step_name, state, created_at "
                        + "FROM agent_checkpoint WHERE run_id = ? ORDER BY step_no",
                CHECKPOINT_MAPPER, runId);
    }

    private static LlmDegradationReason toReason(String value) {
        return value == null || value.isBlank() ? null : LlmDegradationReason.valueOf(value);
    }
}
