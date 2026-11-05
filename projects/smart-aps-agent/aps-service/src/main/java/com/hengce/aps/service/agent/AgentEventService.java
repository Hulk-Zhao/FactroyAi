package com.hengce.aps.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRow;
import com.hengce.aps.service.audit.AuditEventType;
import com.hengce.aps.service.audit.AuditValues;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 异常事件上报服务。
 *
 * <p>事件直接落到既有 {@code audit_log}（C31），不新建平行事件表；租户与审计操作人
 * 均取自服务端上下文，请求体中的 {@code userId}/{@code tenantId} 一律拒绝。
 *
 * <p>幂等：事件标识即 {@code Idempotency-Key}，写入 {@code resource_id}；
 * 同一租户 + 同一事件键在事务咨询锁保护下“先查后写”，重复上报返回既有审计行，
 * 不新增记录。不新增唯一索引，避免改动既有 {@code audit_log} 语义。</p>
 */
@Service
public class AgentEventService {

    /**
     * 允许上报的事件类型：只接受系统内真实发生的动作族，避免客户端自造异常。
     *
     * <p>订单状态变更、求解完成、计划发布、任务调整这四类动作在领域侧已有真实审计记录，
     * 本端点作为内部事件汇（不引入 Redis Stream），由调用方携带对应动作与幂等键上报。</p>
     */
    public static final Set<String> EVENT_TYPES = Set.of(
            "ORDER_STATUS_CHANGED", "SOLVE_TASK_FINISHED", "PLAN_PUBLISHED", "TASK_ADJUSTED");

    /**
     * 审计动作编码。
     */
    public static final String AUDIT_ACTION = AuditEventType.AGENT_EVENT_REPORTED.name();

    /**
     * 审计资源类型，C45 扩展值。
     */
    public static final String AUDIT_RESOURCE_TYPE = AuditEventType.AGENT_EVENT_REPORTED.getResourceType();

    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper;

    /**
     * 构造服务。
     *
     * @param auditLogRepository 审计日志持久层
     * @param objectMapper JSON 序列化器
     */
    public AgentEventService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 上报一条异常事件；重复上报不新增行。
     *
     * @param request 事件请求
     * @param idempotencyKey 事件幂等键（即事件标识）
     * @return 审计证据视图
     */
    @Transactional
    public AgentEventView report(AgentEventRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (request.userId() != null || request.tenantId() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "userId/tenantId 由认证上下文决定，请求体不得指定");
        }
        String eventType = normalize(request.eventType());
        if (eventType == null || !EVENT_TYPES.contains(eventType)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "eventType 必须是 " + String.join("/", EVENT_TYPES.stream().sorted().toList())
                            + " 之一");
        }
        if (request.occurredAt() == null || request.occurredAt().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "occurredAt 不能为空");
        }
        OffsetDateTime occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(request.occurredAt().trim());
        } catch (DateTimeParseException exception) {
            throw new BizException(ErrorCode.BAD_REQUEST, "occurredAt 必须是带时区的 ISO-8601 时间");
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        Map<String, Object> payload = toPayloadMap(request.payload());
        UUID tenantId = TenantContext.getTenantId();

        auditLogRepository.lockIdempotencyKey(tenantId, key);
        Optional<AuditLogRow> existing =
                auditLogRepository.findFirstByResource(tenantId, AUDIT_ACTION, key);
        if (existing.isPresent()) {
            return toView(tenantId, existing.get(), true);
        }

        Map<String, Object> storedPayload = AuditValues.redact(payload);
        Map<String, Object> afterValue = new LinkedHashMap<>();
        afterValue.put("schemaVersion", 1);
        afterValue.put("eventType", eventType);
        afterValue.put("orderNo", normalize(request.orderNo()));
        afterValue.put("resourceCode", normalize(request.resourceCode()));
        afterValue.put("occurredAt", occurredAt.toString());
        afterValue.put("payload", storedPayload);
        afterValue.put("reportedAt", OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
        auditLogRepository.record(tenantId, AUDIT_ACTION, AUDIT_RESOURCE_TYPE, key, null, afterValue);

        AuditLogRow row = auditLogRepository.findFirstByResource(tenantId, AUDIT_ACTION, key)
                .orElseThrow(() -> new IllegalStateException("Agent 事件写入后无法回读: " + key));
        return toView(tenantId, row, false);
    }

    private static AgentEventView toView(UUID tenantId, AuditLogRow row, boolean duplicate) {
        Map<String, Object> after = row.afterValue() == null ? Map.of() : row.afterValue();
        Object rawPayload = after.get("payload");
        Map<String, Object> payload = rawPayload instanceof Map<?, ?> map
                ? toStringMap(map) : Map.of();
        return new AgentEventView(row.id(), tenantId, row.userId(), row.resourceId(),
                asString(after.get("eventType")), asString(after.get("orderNo")),
                asString(after.get("resourceCode")), asString(after.get("occurredAt")),
                duplicate, row.createdAt(), payload);
    }

    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> toPayloadMap(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return Map.of();
        }
        if (!payload.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "payload 必须是 JSON 对象");
        }
        return objectMapper.convertValue(payload, MAP_TYPE);
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        String key = normalize(idempotencyKey);
        if (key == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能为空");
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Idempotency-Key 必须为 8-128 位 [A-Za-z0-9._:-]");
        }
        return key;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
