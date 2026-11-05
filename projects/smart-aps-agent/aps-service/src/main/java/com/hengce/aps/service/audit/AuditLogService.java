package com.hengce.aps.service.audit;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogPage;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计服务（C31）：服务层同事务写入、只追加、敏感键脱敏、分页查询。
 *
 * <p>{@link #recordLoginFailed} 使用独立事务，避免登录失败审计影响 401 响应。</p>
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 构造服务。
     *
     * @param auditLogRepository 审计持久层
     */
    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 写入审计（当前事务内），操作人取自 {@code AuditContext}。
     *
     * @param eventType 事件类型
     * @param resourceId 资源业务标识
     * @param beforeValue 变更前快照
     * @param afterValue 变更后快照
     */
    @Transactional
    public void record(AuditEventType eventType, String resourceId,
                       Map<String, Object> beforeValue, Map<String, Object> afterValue) {
        auditLogRepository.record(TenantContext.getTenantId(), eventType.name(),
                eventType.getResourceType(), resourceId,
                AuditValues.redact(beforeValue), AuditValues.redact(afterValue));
    }

    /**
     * 写入审计（显式操作人），用于登录成功等尚未建立审计上下文的场景。
     *
     * @param userId 操作人
     * @param eventType 事件类型
     * @param resourceId 资源业务标识
     * @param beforeValue 变更前快照
     * @param afterValue 变更后快照
     */
    @Transactional
    public void recordForUser(UUID userId, AuditEventType eventType, String resourceId,
                              Map<String, Object> beforeValue, Map<String, Object> afterValue) {
        auditLogRepository.record(TenantContext.getTenantId(), userId, eventType.name(),
                eventType.getResourceType(), resourceId,
                AuditValues.redact(beforeValue), AuditValues.redact(afterValue));
    }

    /**
     * 写入登录失败审计；独立事务，登录失败响应不因审计失败而改变。
     *
     * @param tenantId 租户
     * @param userId 命中的用户，可为空
     * @param username 尝试登录名（作为资源业务标识）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailed(UUID tenantId, UUID userId, String username) {
        auditLogRepository.record(tenantId, userId, AuditEventType.AUTH_LOGIN_FAILED.name(),
                AuditEventType.AUTH_LOGIN_FAILED.getResourceType(), username, null, null);
    }

    /**
     * 分页查询审计日志。
     *
     * @param query 分页参数
     * @param action 动作精确匹配
     * @param resourceType 资源类型精确匹配
     * @param resourceId 资源标识精确匹配
     * @param userId 操作人精确匹配
     * @param from 起始时间（含）
     * @param to 结束时间（不含）
     * @param sort 仅 {@code createdAt,asc|desc}，默认 desc
     * @return 分页结果
     */
    public PageResult<AuditLogView> query(PageQuery query, String action, String resourceType,
                                          String resourceId, UUID userId, OffsetDateTime from,
                                          OffsetDateTime to, String sort) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        boolean ascending = resolveAscending(sort);
        AuditLogPage page = auditLogRepository.query(TenantContext.getTenantId(), action, resourceType,
                resourceId, userId, from, to, ascending, normalized.offset(), normalized.getSize());
        List<AuditLogView> items = page.items().stream().map(AuditLogService::toView).toList();
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), page.total(), 0);
    }

    private static boolean resolveAscending(String sort) {
        if (sort == null || sort.isBlank()) {
            return false;
        }
        String normalized = sort.trim();
        if ("createdAt,asc".equals(normalized)) {
            return true;
        }
        if ("createdAt,desc".equals(normalized)) {
            return false;
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "sort 仅支持 createdAt,asc 或 createdAt,desc");
    }

    private static AuditLogView toView(AuditLogRow row) {
        return new AuditLogView(row.id(), row.traceId(), row.userId(), row.action(), row.resourceType(),
                row.resourceId(), row.beforeValue(), row.afterValue(), row.clientIp(), row.createdAt());
    }
}
