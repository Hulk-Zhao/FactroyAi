package com.hengce.aps.agent.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 步骤 1：取数，把业务上下文放入 {@code state.variables}。
 */
public final class LoadContextStep implements AgentStep {

    private final ContextSource contextSource;

    /**
     * 构造步骤。
     *
     * @param contextSource 上下文来源
     */
    public LoadContextStep(ContextSource contextSource) {
        this.contextSource = contextSource;
    }

    @Override
    public int number() {
        return 1;
    }

    @Override
    public String name() {
        return "load-context";
    }

    @Override
    public void execute(AgentRunState state) {
        Map<String, Object> loaded = contextSource.load(state);
        state.setVariables(loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded));
    }
}
