package com.hengce.aps.agent.orchestration;

import java.util.Map;

/**
 * 默认上下文实现：不取任何业务数据，仅用于框架骨架与无业务接入场景。
 */
public final class EmptyContextSource implements ContextSource {

    @Override
    public Map<String, Object> load(AgentRunState state) {
        return Map.of();
    }
}
