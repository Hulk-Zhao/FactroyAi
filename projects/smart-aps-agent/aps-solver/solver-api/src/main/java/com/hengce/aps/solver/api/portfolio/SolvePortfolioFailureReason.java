package com.hengce.aps.solver.api.portfolio;

/**
 * C54 候选失败原因。
 */
public enum SolvePortfolioFailureReason {
    SOLVER_BUSY,
    TIMEOUT,
    OOM,
    ENGINE_ERROR,
    REJECTED_BY_ADMISSION
}
