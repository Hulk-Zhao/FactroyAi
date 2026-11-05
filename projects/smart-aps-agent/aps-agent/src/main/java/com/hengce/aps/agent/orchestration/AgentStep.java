package com.hengce.aps.agent.orchestration;

/**
 * 编排中的单个步骤。
 *
 * <p>步骤号从 1 开始且必须连续；步骤抛异常时整次运行标记 FAILED，
 * 已落检查点的前缀步骤在续跑时不会重复执行。</p>
 */
public interface AgentStep {

    /**
     * 步骤号，从 1 开始连续递增。
     *
     * @return 步骤号
     */
    int number();

    /**
     * 步骤名（落检查点与日志使用）。
     *
     * @return 稳定短名
     */
    String name();

    /**
     * 执行步骤；通过修改 {@link AgentRunState} 传递结果。
     *
     * @param state 运行状态
     */
    void execute(AgentRunState state);
}
