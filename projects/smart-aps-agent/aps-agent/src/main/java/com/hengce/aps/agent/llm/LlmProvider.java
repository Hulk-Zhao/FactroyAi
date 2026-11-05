package com.hengce.aps.agent.llm;

/**
 * LLM 可插拔 SPI：只做「一段提示词进、一段文本出」，不承载业务语义。
 *
 * <p>实现类必须是真实 HTTP 调用；「不可用」通过 {@link #available()} 表达，
 * 而非返回伪造的成功响应。默认 {@code none} 实现见 {@link DisabledLlmProvider}。</p>
 */
public interface LlmProvider {

    /**
     * provider 标识，与配置 {@code aps.agent.llm.provider} 取值一致。
     *
     * @return 小写标识，例如 {@code deepseek}
     */
    String name();

    /**
     * 当前配置是否具备调用条件（地址/密钥齐备）。仅表示配置完整，不代表网络可达。
     *
     * @return true 表示可发起调用
     */
    boolean available();

    /**
     * 发起一次同步调用。
     *
     * @param request 提示词与输出上限
     * @return 模型原始结果
     * @throws LlmCallException 网络、状态码或解析失败
     */
    LlmResponse complete(LlmRequest request);
}
