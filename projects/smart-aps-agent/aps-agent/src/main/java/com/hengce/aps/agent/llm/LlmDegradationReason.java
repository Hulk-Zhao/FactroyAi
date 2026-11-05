package com.hengce.aps.agent.llm;

/**
 * LLM 降级原因：上层据此决定「保留原文 / 提示不可用」，并作为埋点字段。
 *
 * <p>语义冻结见 {@code docs/api/contract-freeze.md} C43。</p>
 */
public enum LlmDegradationReason {

    /**
     * 显式关闭：{@code aps.agent.llm.provider=none}。
     */
    PROVIDER_DISABLED,

    /**
     * 未配置或名称未知（含配置为空、拼写错误）。
     */
    NO_PROVIDER_CONFIGURED,

    /**
     * 名称有效但配置不完整（缺 base-url / api-key）。
     */
    PROVIDER_UNAVAILABLE,

    /**
     * 非 200 状态码、网络 IO 失败等。
     */
    PROVIDER_ERROR,

    /**
     * 调用超时。
     */
    TIMEOUT,

    /**
     * HTTP 200 但输出为空。
     */
    EMPTY_RESPONSE,

    /**
     * 输出不是合法 JSON 对象（结构化校验未通过）；此时 output 保留原始文本。
     */
    INVALID_OUTPUT
}
