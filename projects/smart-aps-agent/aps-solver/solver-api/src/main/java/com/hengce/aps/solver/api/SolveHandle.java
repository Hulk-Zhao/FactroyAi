package com.hengce.aps.solver.api;

import java.util.UUID;

/**
 * 异步求解句柄；M1 仅作为接口占位，异步生命周期由 aps-service 的求解任务调度承接。
 *
 * @param handleId 句柄标识
 * @param engine 引擎类型
 * @param status 句柄状态：PENDING / RUNNING / SOLVED / FAILED / TIMEOUT / CANCELLED
 */
public record SolveHandle(
        UUID handleId,
        SolverEngine engine,
        String status
) {
}
