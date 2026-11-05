package com.hengce.aps.agent.testsupport;

import com.hengce.aps.agent.checkpoint.AgentCheckpointRecord;
import com.hengce.aps.agent.checkpoint.AgentRunRecord;
import com.hengce.aps.agent.checkpoint.AgentRunStatus;
import com.hengce.aps.agent.checkpoint.CheckpointStore;
import com.hengce.aps.agent.llm.LlmDegradationReason;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 测试专用内存检查点存储：与 {@code JdbcCheckpointStore} 语义一致，仅用于无数据库的单测。
 *
 * <p>仅在 src/test 作用域存在。</p>
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<UUID, AgentRunRecord> runs = new LinkedHashMap<>();

    private final Map<UUID, Map<Integer, AgentCheckpointRecord>> checkpoints = new LinkedHashMap<>();

    @Override
    public AgentRunRecord createRun(UUID tenantId, String promptKey, String variablesJson) {
        AgentRunRecord run = new AgentRunRecord(UUID.randomUUID(), tenantId, AgentRunStatus.RUNNING, promptKey,
                null, null, null, 0, variablesJson == null ? "{}" : variablesJson, null);
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public Optional<AgentRunRecord> findRun(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public void updateRun(UUID runId, AgentRunStatus status, Integer promptVersion, String provider,
                          LlmDegradationReason degradationReason, int lastCompletedStep, String errorMessage) {
        AgentRunRecord current = runs.get(runId);
        if (current == null) {
            return;
        }
        runs.put(runId, new AgentRunRecord(runId, current.tenantId(), status, current.promptKey(),
                promptVersion, provider, degradationReason, lastCompletedStep, current.variablesJson(),
                errorMessage));
    }

    @Override
    public void saveCheckpoint(UUID runId, UUID tenantId, int stepNo, String stepName, String stateJson) {
        checkpoints.computeIfAbsent(runId, key -> new LinkedHashMap<>())
                .put(stepNo, new AgentCheckpointRecord(runId, tenantId, stepNo, stepName, stateJson,
                        OffsetDateTime.now()));
    }

    @Override
    public Optional<AgentCheckpointRecord> latestCheckpoint(UUID runId) {
        return checkpoints.getOrDefault(runId, Map.of()).values().stream()
                .max(Comparator.comparingInt(AgentCheckpointRecord::stepNo));
    }

    @Override
    public List<AgentCheckpointRecord> checkpoints(UUID runId) {
        List<AgentCheckpointRecord> rows = new ArrayList<>(checkpoints.getOrDefault(runId, Map.of()).values());
        rows.sort(Comparator.comparingInt(AgentCheckpointRecord::stepNo));
        return rows;
    }

    /**
     * 检查点数量。
     *
     * @param runId 运行标识
     * @return 数量
     */
    public int checkpointCount(UUID runId) {
        return checkpoints.getOrDefault(runId, Map.of()).size();
    }
}
