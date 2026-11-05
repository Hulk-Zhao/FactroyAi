package com.hengce.aps.agent.llm;

/**
 * provider 返回的原始结果；token 用量可能为空（服务端未返回 usage 时）。
 *
 * @param text 模型输出文本
 * @param model 实际模型名
 * @param promptTokens 输入 token 数，未知为 null
 * @param completionTokens 输出 token 数，未知为 null
 * @param latencyMillis 本 provider 内部计时的调用耗时（毫秒）
 */
public record LlmResponse(String text, String model, Integer promptTokens, Integer completionTokens,
                          long latencyMillis) {
}
