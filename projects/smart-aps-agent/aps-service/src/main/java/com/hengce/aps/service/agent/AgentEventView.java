package com.hengce.aps.service.agent;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 异常事件上报结果，证据直接来自 {@code audit_log} 表。
 *
 * @param auditLogId 审计日志主键
 * @param tenantId 租户标识（服务端上下文）
 * @param userId 审计操作人（服务端认证上下文；API Key/off 模式为空）
 * @param eventId 事件标识，等于请求的 {@code Idempotency-Key}
 * @param eventType 事件类型
 * @param orderNo 关联订单号
 * @param resourceCode 关联资源编码
 * @param occurredAt 事件发生时间
 * @param duplicate 是否为重复上报（true 表示本次未新增行）
 * @param recordedAt 审计落库时间
 * @param payload 脱敏后的负载快照
 */
public record AgentEventView(long auditLogId, UUID tenantId, UUID userId, String eventId,
                             String eventType, String orderNo, String resourceCode, String occurredAt,
                             boolean duplicate, OffsetDateTime recordedAt, Map<String, Object> payload) {
}
