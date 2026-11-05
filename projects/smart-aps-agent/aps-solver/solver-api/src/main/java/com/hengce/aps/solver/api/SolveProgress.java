package com.hengce.aps.solver.api;

import java.util.UUID;

/**
 * 异步求解进度；M1 仅作为接口占位，不实现进度推送。
 *
 * @param handleId 句柄标识
 * @param status 状态：PENDING / RUNNING / SOLVED / FAILED / TIMEOUT / CANCELLED
 * @param progressPercent 进度百分比，0–100
 * @param message 附加说明
 */
public record SolveProgress(
        UUID handleId,
        String status,
        int progressPercent,
        String message
) {
}
