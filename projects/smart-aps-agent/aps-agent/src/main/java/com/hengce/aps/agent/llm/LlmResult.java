package com.hengce.aps.agent.llm;

/**
 * 网关调用结果：成功与降级共用同一形状，调用方无需 try/catch。
 *
 * @param available 是否拿到可用输出
 * @param provider 实际 provider 名（未配置时为空串）
 * @param output 模型输出；降级时为 null
 * @param degradationReason 降级原因；成功时为 null
 * @param degradationDetail 脱敏后的降级说明；成功时为 null
 * @param latencyMillis 调用耗时（毫秒）
 * @param promptTokens 输入 token 数，未知为 null
 * @param completionTokens 输出 token 数，未知为 null
 */
public record LlmResult(boolean available, String provider, String output,
                        LlmDegradationReason degradationReason, String degradationDetail,
                        long latencyMillis, Integer promptTokens, Integer completionTokens) {

    /**
     * 构造成功结果。
     *
     * @param provider provider 名
     * @param response 原始响应
     * @return 成功结果
     */
    public static LlmResult success(String provider, LlmResponse response) {
        return new LlmResult(true, provider, response.text(), null, null,
                response.latencyMillis(), response.promptTokens(), response.completionTokens());
    }

    /**
     * 构造降级结果。
     *
     * @param provider provider 名，可为空
     * @param reason 降级原因
     * @param detail 脱敏说明
     * @param latencyMillis 已耗时长
     * @return 降级结果
     */
    public static LlmResult degraded(String provider, LlmDegradationReason reason, String detail,
                                     long latencyMillis) {
        return new LlmResult(false, provider, null, reason, detail, latencyMillis, null, null);
    }
}
