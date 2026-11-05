package com.hengce.aps.infrastructure.persistence.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志查询行（C31）。
 *
 * <p>{@code id} 为 int64，对齐 {@code audit_log.id BIGSERIAL}。</p>
 *
 * @param id 主键
 * @param userId 操作人，可为空
 * @param action 动作编码
 * @param resourceType 资源类型
 * @param resourceId 资源业务标识
 * @param beforeValue 变更前快照
 * @param afterValue 变更后快照
 * @param clientIp 客户端地址（DB 列 {@code ip}）
 * @param traceId 链路标识
 * @param createdAt 创建时间
 */
public record AuditLogRow(long id, UUID userId, String action, String resourceType, String resourceId,
                          Map<String, Object> beforeValue, Map<String, Object> afterValue,
                          String clientIp, String traceId, OffsetDateTime createdAt) {
}
