package com.hengce.aps.agent.checkpoint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 单个步骤完成后的检查点（表 {@code agent_checkpoint}）。
 *
 * <p>以整份 {@code AgentRunState} 的 JSON 快照落库，续跑时直接反序列化，
 * 不依赖各步骤的中间表结构。</p>
 *
 * @param runId 运行标识
 * @param tenantId 租户
 * @param stepNo 步骤号（从 1 开始）
 * @param stepName 步骤名
 * @param stateJson 状态快照 JSON
 * @param createdAt 落库时间
 */
public record AgentCheckpointRecord(UUID runId, UUID tenantId, int stepNo, String stepName,
                                    String stateJson, OffsetDateTime createdAt) {
}
