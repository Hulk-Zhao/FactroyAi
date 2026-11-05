package com.hengce.aps.service.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志响应项（C31 / 25.2.3）。
 *
 * <p>字段名沿用 yaml；{@code id} 为 int64，{@code ip}/{@code trace_id} 映射为
 * {@code clientIp}/{@code traceId}，{@code user_agent} 不进 API。</p>
 *
 * @param id 主键（int64）
 * @param traceId 链路标识
 * @param userId 操作人
 * @param action 动作编码
 * @param resourceType 资源类型
 * @param resourceId 资源业务标识
 * @param beforeValue 变更前快照
 * @param afterValue 变更后快照
 * @param clientIp 客户端地址
 * @param createdAt 创建时间
 */
public record AuditLogView(long id, String traceId, UUID userId, String action, String resourceType,
                           String resourceId, Map<String, Object> beforeValue,
                           Map<String, Object> afterValue, String clientIp, OffsetDateTime createdAt) {
}
