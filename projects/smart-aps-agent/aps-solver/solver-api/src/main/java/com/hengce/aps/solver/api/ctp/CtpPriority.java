package com.hengce.aps.solver.api.ctp;

/**
 * C53 询单优先级（与 {@code OrderPriority} 同值，避免 solver-api 依赖服务层枚举）。
 */
public enum CtpPriority {
    URGENT,
    HIGH,
    NORMAL,
    LOW
}
