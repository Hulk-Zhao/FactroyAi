package com.hengce.aps.service.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 异常事件上报请求（POST /agent/events）。
 *
 * <p>{@code userId}/{@code tenantId} 仅用于识别并拒绝客户端伪造身份，正常请求不得传值；
 * 审计操作人与租户一律取自服务端认证上下文。</p>
 *
 * @param eventType 事件类型，见 {@link AgentEventService#EVENT_TYPES}
 * @param orderNo 关联订单号，可空
 * @param resourceCode 关联资源编码，可空
 * @param occurredAt 事件发生时间（ISO-8601，必须带时区）
 * @param payload 事件附加负载，可空
 * @param userId 客户端不得指定
 * @param tenantId 客户端不得指定
 */
public record AgentEventRequest(String eventType, String orderNo, String resourceCode,
                                String occurredAt, JsonNode payload, String userId, String tenantId) {
}
