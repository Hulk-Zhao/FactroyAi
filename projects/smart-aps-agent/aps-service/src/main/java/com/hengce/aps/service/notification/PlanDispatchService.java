package com.hengce.aps.service.notification;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.DispatchAggregateRow;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import com.hengce.aps.service.audit.AuditValues;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划下发服务（C33 §25.5）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>只允许对 {@code PUBLISHED} 计划下发，缺 {@code Idempotency-Key} 直接 40000；</li>
 *   <li>只写 outbox（{@code status=QUEUED}），本请求内不真实发送；</li>
 *   <li>幂等：{@code dispatchId} 由 {@code tenant + planId + Idempotency-Key} 确定性生成，重复 key
 *       命中既有行即返回首次 batch，不再入队；并发写入由
 *       {@code uq_notification_outbox_target} + {@code ON CONFLICT DO NOTHING} 兜底；</li>
 *   <li>{@code GET /plans/{id}/dispatches} 返回聚合状态，不修改 C24 {@code PlanDetailView}。</li>
 * </ul>
 */
@Service
public class PlanDispatchService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private static final Set<String> CHANNELS = Set.of("EMAIL", "SMS", "FEISHU");

    private static final int MAX_TARGETS = 50;

    private static final int MAX_ADDRESS_LENGTH = 256;

    private static final String STATUS_QUEUED = "QUEUED";

    private final PlanVersionRepository planVersionRepository;

    private final NotificationOutboxRepository outboxRepository;

    private final AuditLogRepository auditLogRepository;

    /**
     * 构造服务。
     *
     * @param planVersionRepository 计划版本持久层
     * @param outboxRepository outbox 持久层
     * @param auditLogRepository 审计持久层
     */
    public PlanDispatchService(PlanVersionRepository planVersionRepository,
                               NotificationOutboxRepository outboxRepository,
                               AuditLogRepository auditLogRepository) {
        this.planVersionRepository = planVersionRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 下发计划：校验 PUBLISHED，写入 outbox，写审计。
     *
     * @param planId 计划版本主键
     * @param idempotencyKey 幂等键，必填
     * @param targets 目标列表，1–50
     * @param message 附加说明，可空
     * @return 下发响应
     */
    @Transactional
    public DispatchResultView dispatch(UUID planId, String idempotencyKey,
                                       List<DispatchTargetCommand> targets, String message) {
        validateKey(idempotencyKey);
        UUID tenantId = TenantContext.getTenantId();
        PlanVersionRow plan = planVersionRepository.find(tenantId, planId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "计划不存在: " + planId));
        if (!"PUBLISHED".equals(plan.status())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅 PUBLISHED 计划可下发，当前状态: " + plan.status());
        }
        List<DispatchTargetCommand> normalized = normalizeTargets(targets);

        UUID dispatchId = deterministicDispatchId(tenantId, planId, idempotencyKey.trim());
        long existing = outboxRepository.countByDispatch(tenantId, dispatchId);
        if (existing > 0) {
            return new DispatchResultView(dispatchId, planId, STATUS_QUEUED, (int) existing,
                    outboxRepository.findQueuedAt(tenantId, dispatchId).orElse(now()));
        }

        OffsetDateTime queuedAt = now();
        int inserted = 0;
        for (DispatchTargetCommand target : normalized) {
            inserted += outboxRepository.insertTarget(tenantId, dispatchId, planId, target.channel(),
                    target.address(), message, queuedAt);
        }
        if (inserted == 0) {
            // 并发下另一请求已写入同一 dispatchId：返回既有批次，不再入队
            long rows = outboxRepository.countByDispatch(tenantId, dispatchId);
            return new DispatchResultView(dispatchId, planId, STATUS_QUEUED, (int) rows,
                    outboxRepository.findQueuedAt(tenantId, dispatchId).orElse(queuedAt));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dispatchId", dispatchId.toString());
        after.put("channels", new ArrayList<>(new LinkedHashSet<>(
                normalized.stream().map(DispatchTargetCommand::channel).toList())));
        after.put("targetCount", inserted);
        auditLogRepository.record(tenantId, "PLAN_DISPATCHED", "PLAN", planId.toString(), null,
                AuditValues.redact(after));

        return new DispatchResultView(dispatchId, planId, STATUS_QUEUED, inserted, queuedAt);
    }

    /**
     * 分页查询计划的下发批次聚合状态。
     *
     * @param planId 计划版本主键
     * @param query 分页参数，可空
     * @return 分页聚合状态
     */
    public PageResult<DispatchSummaryView> listDispatches(UUID planId, PageQuery query) {
        PageQuery normalized = query == null ? new PageQuery() : query.normalize();
        UUID tenantId = TenantContext.getTenantId();
        planVersionRepository.find(tenantId, planId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "计划不存在: " + planId));
        long total = outboxRepository.countDispatches(tenantId, planId);
        List<DispatchSummaryView> items = outboxRepository
                .pageDispatches(tenantId, planId, normalized.offset(), normalized.getSize())
                .stream().map(row -> toSummary(planId, row)).toList();
        return PageResult.of(items, normalized, total);
    }

    private static DispatchSummaryView toSummary(UUID planId, DispatchAggregateRow row) {
        return new DispatchSummaryView(row.dispatchId(), planId,
                aggregateStatus(row.sentCount(), row.failedCount(), row.queuedCount()),
                row.targetCount(), row.sentCount(), row.failedCount(), row.createdAt());
    }

    private static String aggregateStatus(int sent, int failed, int queued) {
        if (queued > 0) {
            return STATUS_QUEUED;
        }
        if (sent > 0 && failed > 0) {
            return "PARTIAL";
        }
        return sent > 0 ? "SENT" : "FAILED";
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能为空");
        }
        if (!KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Idempotency-Key 需为 8-128 位字母、数字或 . _ : -");
        }
    }

    private static List<DispatchTargetCommand> normalizeTargets(List<DispatchTargetCommand> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "targets 至少 1 项");
        }
        if (targets.size() > MAX_TARGETS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "targets 最多 " + MAX_TARGETS + " 项");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<DispatchTargetCommand> normalized = new ArrayList<>(targets.size());
        for (DispatchTargetCommand target : targets) {
            String channel = target.channel() == null ? null
                    : target.channel().trim().toUpperCase();
            if (!CHANNELS.contains(channel)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "channel 仅允许 EMAIL / SMS / FEISHU");
            }
            String address = target.address() == null ? null : target.address().trim();
            if (address == null || address.isEmpty()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "address 不能为空");
            }
            if (address.length() > MAX_ADDRESS_LENGTH) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "address 长度不能超过 " + MAX_ADDRESS_LENGTH);
            }
            if (!seen.add(channel + "|" + address)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "同一 (channel,address) 不得重复: " + channel + "/" + address);
            }
            normalized.add(new DispatchTargetCommand(channel, address));
        }
        return normalized;
    }

    private static UUID deterministicDispatchId(UUID tenantId, UUID planId, String key) {
        return UUID.nameUUIDFromBytes(("aps-dispatch:" + tenantId + ":" + planId + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 下发目标命令。
     *
     * @param channel EMAIL / SMS / FEISHU
     * @param address 目标地址
     */
    public record DispatchTargetCommand(String channel, String address) {
    }
}
