package com.hengce.aps.service.agent;

import java.util.Map;
import java.util.UUID;

/**
 * 对话式 what-if 请求（POST /agent/whatif）。
 *
 * @param sandboxId 必须为 WHAT_IF 场景；BASELINE/ARCHIVED 一律拒绝，避免直接改主计划
 * @param question 用户问题，仅用于回显与摘要，不参与求解
 * @param parameters 受支持的求解参数；未知键由服务端返回 42200 并点名
 */
public record AgentWhatIfRequest(UUID sandboxId, String question, Map<String, Object> parameters) {
}
