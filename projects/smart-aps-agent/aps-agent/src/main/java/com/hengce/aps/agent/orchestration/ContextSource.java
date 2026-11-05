package com.hengce.aps.agent.orchestration;

import java.util.Map;

/**
 * 步骤 1 的取数抽象：由业务车道提供上下文（订单、任务、约束解释原文等）。
 *
 * <p>默认实现 {@link EmptyContextSource} 返回空上下文，保证框架可独立跑通；
 * 业务实现只需注入自己的 {@code ContextSource} Bean，不改编排代码。</p>
 */
public interface ContextSource {

    /**
     * 取数。
     *
     * @param state 当前运行状态（可读取 promptKey 等）
     * @return 上下文变量；返回 null 视为空
     */
    Map<String, Object> load(AgentRunState state);
}
