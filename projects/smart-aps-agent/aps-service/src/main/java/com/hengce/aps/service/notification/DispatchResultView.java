package com.hengce.aps.service.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 下发响应（C33 §25.5.1）。
 *
 * @param dispatchId 下发批次标识
 * @param planId 计划版本主键
 * @param status 固定 {@code QUEUED}
 * @param targetCount 入队目标数
 * @param queuedAt 入队时间
 */
public record DispatchResultView(
        UUID dispatchId,
        UUID planId,
        String status,
        int targetCount,
        OffsetDateTime queuedAt) {
}
