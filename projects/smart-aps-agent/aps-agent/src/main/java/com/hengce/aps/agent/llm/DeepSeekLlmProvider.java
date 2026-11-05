package com.hengce.aps.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DeepSeek（OpenAI 兼容）实现；真实 HTTP 调用，默认不启用。
 */
public final class DeepSeekLlmProvider extends AbstractOpenAiCompatibleLlmProvider {

    /**
     * provider 名。
     */
    public static final String NAME = "deepseek";

    /**
     * 构造 DeepSeek provider。
     *
     * @param baseUrl 基础地址
     * @param apiKey 密钥
     * @param model 模型名
     * @param timeoutMillis 超时毫秒
     * @param objectMapper JSON 序列化器
     */
    public DeepSeekLlmProvider(String baseUrl, String apiKey, String model, long timeoutMillis,
                               ObjectMapper objectMapper) {
        super(NAME, baseUrl, apiKey, model, timeoutMillis, objectMapper);
    }
}
