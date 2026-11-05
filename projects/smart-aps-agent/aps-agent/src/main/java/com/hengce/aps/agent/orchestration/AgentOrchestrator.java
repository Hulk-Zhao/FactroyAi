package com.hengce.aps.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.agent.checkpoint.AgentCheckpointRecord;
import com.hengce.aps.agent.checkpoint.AgentRunRecord;
import com.hengce.aps.agent.checkpoint.AgentRunStatus;
import com.hengce.aps.agent.checkpoint.CheckpointStore;
import com.hengce.aps.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最小分步编排：取数 → 组装 Prompt → 调 provider → 结构化校验 → 逐步落检查点。
 *
 * <p>检查点策略：每步成功后把整份 {@link AgentRunState} 序列化落库；
 * {@link #resume(UUID)} 从步骤号最大的检查点恢复并只执行剩余步骤。
 * LLM 不可用不抛异常，而是返回 {@link AgentRunStatus#DEGRADED} 结果。
 * 仅持久化基础设施异常会以 {@link AgentRunStatus#FAILED} 结果返回（含原因），
 * 便于上层重试续跑。</p>
 */
public final class AgentOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final int ERROR_LIMIT = 200;

    private final CheckpointStore checkpointStore;

    private final List<AgentStep> steps;

    private final ObjectMapper objectMapper;

    private final String defaultPromptKey;

    private final boolean checkpointEnabled;

    /**
     * 构造编排器。
     *
     * @param checkpointStore 检查点存储
     * @param steps 步骤列表（顺序无关，内部按步骤号排序）
     * @param objectMapper JSON 序列化器
     * @param defaultPromptKey 默认 Prompt 键
     * @param checkpointEnabled 是否每步落检查点
     */
    public AgentOrchestrator(CheckpointStore checkpointStore, List<AgentStep> steps, ObjectMapper objectMapper,
                             String defaultPromptKey, boolean checkpointEnabled) {
        this.checkpointStore = checkpointStore;
        this.objectMapper = objectMapper;
        this.defaultPromptKey = defaultPromptKey;
        this.checkpointEnabled = checkpointEnabled;
        this.steps = validateAndSort(steps);
    }

    /**
     * 使用默认 Prompt 键发起一次新运行。
     *
     * @return 编排结果
     */
    public AgentRunResult run() {
        return run(defaultPromptKey);
    }

    /**
     * 以指定 Prompt 键发起一次新运行。
     *
     * @param promptKey Prompt 键
     * @return 编排结果
     */
    public AgentRunResult run(String promptKey) {
        UUID tenantId = TenantContext.getTenantId();
        AgentRunRecord run = checkpointStore.createRun(tenantId, promptKey, "{}");
        AgentRunState state = new AgentRunState();
        state.setRunId(run.id());
        state.setPromptKey(promptKey);
        return execute(state, tenantId);
    }

    /**
     * 从已有运行的最后一个检查点继续执行。
     *
     * @param runId 运行标识
     * @return 编排结果
     * @throws IllegalArgumentException 运行不存在
     */
    public AgentRunResult resume(UUID runId) {
        AgentRunRecord run = checkpointStore.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 运行不存在: " + runId));
        AgentRunState state = restore(run);
        state.setRunId(run.id());
        state.setPromptKey(run.promptKey());
        return execute(state, run.tenantId());
    }

    private AgentRunResult execute(AgentRunState state, UUID tenantId) {
        UUID runId = state.getRunId();
        try {
            for (AgentStep step : steps) {
                if (step.number() <= state.getLastCompletedStep()) {
                    continue;
                }
                step.execute(state);
                state.setLastCompletedStep(step.number());
                state.setLastStepName(step.name());
                saveCheckpoint(state, tenantId, step);
            }
            AgentRunStatus status = state.isDegraded() ? AgentRunStatus.DEGRADED : AgentRunStatus.COMPLETED;
            checkpointStore.updateRun(runId, status, state.getPromptVersion(), state.getProvider(),
                    state.getDegradationReason(), state.getLastCompletedStep(), null);
            if (state.isDegraded()) {
                LOG.warn("event=agent.run.degraded runId={} provider={} reason={} detail={}",
                        runId, state.getProvider(), state.getDegradationReason(), state.getDegradationDetail());
            } else {
                LOG.info("event=agent.run.completed runId={} provider={} promptVersion={} steps={}",
                        runId, state.getProvider(), state.getPromptVersion(), state.getLastCompletedStep());
            }
            return result(state, status);
        } catch (RuntimeException exception) {
            String message = sanitize(exception.getMessage(), exception.getClass().getSimpleName());
            LOG.error("event=agent.run.failed runId={} step={} message={}",
                    runId, state.getLastStepName(), message);
            safeUpdateFailed(state, message);
            return result(state, AgentRunStatus.FAILED);
        }
    }

    private void saveCheckpoint(AgentRunState state, UUID tenantId, AgentStep step) {
        if (!checkpointEnabled) {
            return;
        }
        checkpointStore.saveCheckpoint(state.getRunId(), tenantId, step.number(), step.name(),
                serialize(state));
    }

    private void safeUpdateFailed(AgentRunState state, String message) {
        try {
            checkpointStore.updateRun(state.getRunId(), AgentRunStatus.FAILED, state.getPromptVersion(),
                    state.getProvider(), state.getDegradationReason(), state.getLastCompletedStep(), message);
        } catch (RuntimeException persistenceError) {
            LOG.warn("event=agent.run.failed.persist_error runId={} error={}",
                    state.getRunId(), persistenceError.getClass().getSimpleName());
        }
    }

    private AgentRunState restore(AgentRunRecord run) {
        Optional<AgentCheckpointRecord> latest = checkpointStore.latestCheckpoint(run.id());
        if (latest.isPresent()) {
            try {
                return objectMapper.readValue(latest.get().stateJson(), AgentRunState.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("检查点状态无法反序列化: " + run.id(), exception);
            }
        }
        AgentRunState state = new AgentRunState();
        try {
            Map<String, Object> variables = objectMapper.readValue(run.variablesJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            state.setVariables(variables);
        } catch (JsonProcessingException exception) {
            state.setVariables(new LinkedHashMap<>());
        }
        return state;
    }

    private String serialize(AgentRunState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 状态无法序列化为 JSON", exception);
        }
    }

    private static AgentRunResult result(AgentRunState state, AgentRunStatus status) {
        return new AgentRunResult(state.getRunId(), status, state.getOutput(), state.isDegraded(),
                state.getDegradationReason(), state.getDegradationDetail(), state.getProvider(),
                state.getPromptVersion(), state.getLastCompletedStep());
    }

    private static String sanitize(String message, String fallback) {
        String value = message == null || message.isBlank() ? fallback : message;
        String flattened = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return flattened.length() <= ERROR_LIMIT ? flattened : flattened.substring(0, ERROR_LIMIT);
    }

    private static List<AgentStep> validateAndSort(List<AgentStep> input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Agent 步骤不能为空");
        }
        List<AgentStep> ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparingInt(AgentStep::number));
        Set<Integer> seen = new HashSet<>();
        int expected = 1;
        for (AgentStep step : ordered) {
            if (!seen.add(step.number())) {
                throw new IllegalArgumentException("Agent 步骤号重复: " + step.number());
            }
            if (step.number() != expected) {
                throw new IllegalArgumentException("Agent 步骤号必须从 1 连续递增，期望 " + expected
                        + " 实际 " + step.number());
            }
            expected++;
        }
        return List.copyOf(ordered);
    }
}
