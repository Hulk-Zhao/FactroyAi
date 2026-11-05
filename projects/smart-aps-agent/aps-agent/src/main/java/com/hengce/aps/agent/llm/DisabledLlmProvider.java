package com.hengce.aps.agent.llm;

/**
 * 显式关闭时的 no-op 实现：永远不可用，永远拒绝调用。
 *
 * <p>它是生产代码中的真实实现位，用于在无凭证环境表达「模型关闭」；
 * 与测试作用域的假 provider 不同，不返回任何伪造输出。</p>
 */
public final class DisabledLlmProvider implements LlmProvider {

    /**
     * 关闭态 provider 名。
     */
    public static final String NAME = "none";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        throw new LlmCallException("llm provider is disabled");
    }
}
