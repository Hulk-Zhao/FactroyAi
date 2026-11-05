package com.hengce.aps.agent.orchestration;

import com.hengce.aps.agent.checkpoint.AgentRunStatus;
import com.hengce.aps.agent.llm.LlmDegradationReason;
import java.util.UUID;

/**
 * 编排结果：上层（如 explain 端点）只读此结构，不感知内部步骤。
 *
 * @param runId 运行标识（可用于续跑或查看检查点）
 * @param status 状态
 * @param output 模型输出；降级时为 null（INVALID_OUTPUT 时保留原文）
 * @param degraded 是否降级
 * @param degradationReason 降级原因，正常为 null
 * @param degradationDetail 脱敏说明，正常为 null
 * @param provider 实际 provider 名
 * @param promptVersion 实际 Prompt 版本
 * @param lastCompletedStep 已完成步骤号
 */
public record AgentRunResult(UUID runId, AgentRunStatus status, String output, boolean degraded,
                             LlmDegradationReason degradationReason, String degradationDetail,
                             String provider, Integer promptVersion, int lastCompletedStep) {
}
