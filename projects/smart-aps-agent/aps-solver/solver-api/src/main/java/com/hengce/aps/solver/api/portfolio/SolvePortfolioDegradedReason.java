package com.hengce.aps.solver.api.portfolio;

/**
 * C54 portfolio 降级原因。
 */
public enum SolvePortfolioDegradedReason {
    DISABLED,
    TASK_SCALE,
    CPU_BUDGET,
    MEMORY_BUDGET,
    SOLVER_BUSY,
    QUEUE_FULL,
    CONFIG_ERROR,
    OOM,
    UNKNOWN
}
