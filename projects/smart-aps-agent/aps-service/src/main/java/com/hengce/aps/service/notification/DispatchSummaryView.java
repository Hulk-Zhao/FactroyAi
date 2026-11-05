package com.hengce.aps.service.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 下发批次聚合状态（C33 §25.5.2）。
 *
 * @param dispatchId 下发批次标识
 * @param planId 计划版本主键
 * @param status QUEUED / SENT / PARTIAL / FAILED
 * @param targetCount 目标数
 * @param sentCount 成功数
 * @param failedCount 失败数
 * @param createdAt 最早入队时间
 */
public record DispatchSummaryView(
        UUID dispatchId,
        UUID planId,
        String status,
        int targetCount,
        int sentCount,
        int failedCount,
        OffsetDateTime createdAt) {
}
