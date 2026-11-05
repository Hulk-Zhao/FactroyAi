package com.hengce.aps.agent.orchestration;

import com.hengce.aps.agent.llm.LlmGateway;
import com.hengce.aps.agent.llm.LlmRequest;
import com.hengce.aps.agent.llm.LlmResult;

/**
 * 步骤 3：调用 LLM。失败不抛出，只把降级原因写入状态，由后续步骤与上层契约消费。
 */
public final class CallLlmStep implements AgentStep {

    private final LlmGateway llmGateway;

    private final int maxTokens;

    /**
     * 构造步骤。
     *
     * @param llmGateway LLM 网关
     * @param maxTokens 输出上限；小于等于 0 时使用 provider 默认
     */
    public CallLlmStep(LlmGateway llmGateway, int maxTokens) {
        this.llmGateway = llmGateway;
        this.maxTokens = maxTokens;
    }

    @Override
    public int number() {
        return 3;
    }

    @Override
    public String name() {
        return "call-llm";
    }

    @Override
    public void execute(AgentRunState state) {
        LlmResult result = llmGateway.call(
                new LlmRequest(state.getComposedPrompt(), maxTokens > 0 ? maxTokens : null));
        state.setProvider(result.provider());
        state.setLatencyMillis(result.latencyMillis());
        state.setPromptTokens(result.promptTokens());
        state.setCompletionTokens(result.completionTokens());
        state.setDegraded(!result.available());
        if (result.available()) {
            state.setOutput(result.output());
            state.setDegradationReason(null);
            state.setDegradationDetail(null);
        } else {
            state.setOutput(null);
            state.setDegradationReason(result.degradationReason());
            state.setDegradationDetail(result.degradationDetail());
        }
    }
}
