package com.hengce.aps.agent.llm;

/**
 * 一次模型调用的最小入参：单轮提示词 + 可选输出上限 + 是否强制 JSON 输出。
 *
 * @param prompt 已组装完成的提示词
 * @param maxTokens 输出上限（token）；null 表示由 provider 默认值决定
 * @param jsonOutput 是否要求上游按 JSON 对象输出（OpenAI 兼容用 response_format，Ollama 用 format=json）
 */
public record LlmRequest(String prompt, Integer maxTokens, boolean jsonOutput) {

    /**
     * 兼容构造：不强制 JSON。
     *
     * @param prompt 提示词
     * @param maxTokens 输出上限
     */
    public LlmRequest(String prompt, Integer maxTokens) {
        this(prompt, maxTokens, false);
    }

    /**
     * 以默认输出上限构造请求。
     *
     * @param prompt 提示词
     * @return 请求
     */
    public static LlmRequest of(String prompt) {
        return new LlmRequest(prompt, null, false);
    }

    /**
     * 要求上游按 JSON 对象输出。
     *
     * @param prompt 提示词
     * @param maxTokens 输出上限
     * @return 请求
     */
    public static LlmRequest json(String prompt, Integer maxTokens) {
        return new LlmRequest(prompt, maxTokens, true);
    }
}
