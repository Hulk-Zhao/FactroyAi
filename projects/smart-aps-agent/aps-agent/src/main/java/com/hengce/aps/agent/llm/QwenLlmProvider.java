package com.hengce.aps.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通义（DashScope OpenAI 兼容模式）实现；真实 HTTP 调用，默认不启用。
 */
public final class QwenLlmProvider extends AbstractOpenAiCompatibleLlmProvider {

    /**
     * provider 名。
     */
    public static final String NAME = "qwen";

    /**
     * 构造通义 provider。
     *
     * @param baseUrl 基础地址（DashScope 兼容模式通常已含 {@code /v1}）
     * @param apiKey 密钥
     * @param model 模型名
     * @param timeoutMillis 超时毫秒
     * @param objectMapper JSON 序列化器
     */
    public QwenLlmProvider(String baseUrl, String apiKey, String model, long timeoutMillis,
                           ObjectMapper objectMapper) {
        super(NAME, baseUrl, apiKey, model, timeoutMillis, objectMapper);
    }
}
