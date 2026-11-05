package com.hengce.aps.agent.checkpoint;

/**
 * 一次 Agent 运行的生命周期状态。
 */
public enum AgentRunStatus {

    /**
     * 执行中（含步骤失败后尚未恢复的状态之外，只有进行中才可继续）。
     */
    RUNNING,

    /**
     * 全部步骤完成且 LLM 输出可用。
     */
    COMPLETED,

    /**
     * 编排跑完但 LLM 不可用或输出不可用，上层需按降级契约处理。
     */
    DEGRADED,

    /**
     * 步骤或检查点基础设施异常；可从最后一个检查点续跑。
     */
    FAILED
}
