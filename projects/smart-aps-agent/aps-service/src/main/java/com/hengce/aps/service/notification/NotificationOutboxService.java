package com.hengce.aps.service.notification;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.OutboxRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * outbox 状态流转服务（C33 §25.5.3）。
 *
 * <p>只处理已到重试时间的 {@code QUEUED} 行：成功 → {@code SENT}；失败且仍有次数 → 保持
 * {@code QUEUED} 并退避 30s；用尽 2 次 → {@code FAILED} 终态。不产生重复行（唯一索引 +
 * 只更新既有行）。M3 不接真实网关，发送器由 {@code aps.notification.sink} 选择 NOOP/FILE。</p>
 */
@Service
public class NotificationOutboxService {

    /**
     * 每目标最多尝试次数（首次 + 1 次重试）。
     */
    public static final int MAX_ATTEMPTS = 2;

    /**
     * 失败退避秒数。
     */
    public static final int BACKOFF_SECONDS = 30;

    private static final int DEFAULT_BATCH = 100;

    private static final int MAX_BATCH = 200;

    private static final int MAX_ERROR_LENGTH = 512;

    private final NotificationOutboxRepository outboxRepository;

    private final NotificationChannelSender channelSender;

    /**
     * 构造服务。
     *
     * @param outboxRepository outbox 持久层
     * @param channelSender 通道实现（NOOP/FILE）
     */
    public NotificationOutboxService(NotificationOutboxRepository outboxRepository,
                                     NotificationChannelSender channelSender) {
        this.outboxRepository = outboxRepository;
        this.channelSender = channelSender;
    }

    /**
     * 处理一批到期 outbox 记录。
     *
     * @param limit 本批上限，非正数取默认 100，最大 200
     * @return 实际处理条数
     */
    @Transactional
    public int processDue(int limit) {
        return processDueBatch(limit).processed();
    }

    /**
     * 处理一批到期 outbox 记录，并返回成功/重试/终态失败的分项计数（供轮询驱动记录日志）。
     *
     * @param limit 本批上限，非正数取默认 100，最大 200
     * @return 本次处理结果
     */
    @Transactional
    public ProcessResult processDueBatch(int limit) {
        UUID tenantId = TenantContext.getTenantId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int batch = limit <= 0 ? DEFAULT_BATCH : Math.min(limit, MAX_BATCH);
        List<OutboxRow> due = outboxRepository.listDue(tenantId, now, batch);
        int processed = 0;
        int sent = 0;
        int retried = 0;
        int failed = 0;
        for (OutboxRow row : due) {
            processed++;
            int attempts = row.attempts() + 1;
            try {
                channelSender.send(row);
                outboxRepository.markSent(tenantId, row.id(), attempts);
                sent++;
            } catch (RuntimeException exception) {
                String error = truncate(exception.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    outboxRepository.markFailed(tenantId, row.id(), attempts, error);
                    failed++;
                } else {
                    outboxRepository.markRetry(tenantId, row.id(), attempts,
                            now.plusSeconds(BACKOFF_SECONDS), error);
                    retried++;
                }
            }
        }
        return new ProcessResult(processed, sent, retried, failed);
    }

    /**
     * 单批处理结果。
     *
     * @param processed 领取并处理的行数
     * @param sent 成功转 {@code SENT} 的行数
     * @param retried 失败但已排下一次重试（仍 {@code QUEUED}）的行数
     * @param failed 尝试次数耗尽转 {@code FAILED} 的行数
     */
    public record ProcessResult(int processed, int sent, int retried, int failed) {
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
