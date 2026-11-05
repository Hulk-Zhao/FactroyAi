package com.hengce.aps.agent.checkpoint;

import com.hengce.aps.agent.llm.LlmDegradationReason;
import java.util.UUID;

/**
 * 一次编排运行的可持久化摘要（表 {@code agent_run}）。
 *
 * @param id 运行标识
 * @param tenantId 租户
 * @param status 状态
 * @param promptKey 使用的 Prompt 键
 * @param promptVersion 实际使用的 Prompt 版本，未知为 null
 * @param provider 实际使用的 provider，未知为 null
 * @param degradationReason 降级原因，正常为 null
 * @param lastCompletedStep 已完成的最后步骤号，0 表示尚未完成任何步骤
 * @param variablesJson 步骤 1 取到的上下文（JSON 文本），用于续跑重建
 * @param errorMessage 失败原因，正常为 null
 */
public record AgentRunRecord(UUID id, UUID tenantId, AgentRunStatus status, String promptKey,
                             Integer promptVersion, String provider,
                             LlmDegradationReason degradationReason, int lastCompletedStep,
                             String variablesJson, String errorMessage) {
}
