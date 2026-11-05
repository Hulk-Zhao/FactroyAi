package com.hengce.aps.agent.checkpoint;

import com.hengce.aps.agent.llm.LlmDegradationReason;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 运行记录与检查点存储。
 *
 * <p>检查点按 {@code (runId, stepNo)} 幂等 upsert；续跑取步骤号最大的那条。</p>
 */
public interface CheckpointStore {

    /**
     * 新建一条 RUNNING 运行记录。
     *
     * @param tenantId 租户
     * @param promptKey Prompt 键
     * @param variablesJson 初始上下文 JSON（通常为 {@code {}}）
     * @return 已落库的运行记录
     */
    AgentRunRecord createRun(UUID tenantId, String promptKey, String variablesJson);

    /**
     * 查询运行记录。
     *
     * @param runId 运行标识
     * @return 不存在时为空
     */
    Optional<AgentRunRecord> findRun(UUID runId);

    /**
     * 更新运行终态与摘要字段。
     *
     * @param runId 运行标识
     * @param status 状态
     * @param promptVersion Prompt 版本，可为 null
     * @param provider provider 名，可为 null
     * @param degradationReason 降级原因，可为 null
     * @param lastCompletedStep 已完成步骤号
     * @param errorMessage 失败原因，可为 null
     */
    void updateRun(UUID runId, AgentRunStatus status, Integer promptVersion, String provider,
                   LlmDegradationReason degradationReason, int lastCompletedStep, String errorMessage);

    /**
     * 落检查点（同 runId + stepNo 覆盖）。
     *
     * @param runId 运行标识
     * @param tenantId 租户
     * @param stepNo 步骤号
     * @param stepName 步骤名
     * @param stateJson 状态快照 JSON
     */
    void saveCheckpoint(UUID runId, UUID tenantId, int stepNo, String stepName, String stateJson);

    /**
     * 取最后一个检查点。
     *
     * @param runId 运行标识
     * @return 无检查点时为空
     */
    Optional<AgentCheckpointRecord> latestCheckpoint(UUID runId);

    /**
     * 列出全部检查点，按步骤号升序。
     *
     * @param runId 运行标识
     * @return 检查点列表
     */
    List<AgentCheckpointRecord> checkpoints(UUID runId);
}
