package com.hengce.aps.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * outbox 轮询驱动（C33，仅 dev 生效）。
 *
 * <p>双门控：{@code aps.notification.scheduler.enabled} 默认 {@code false}（见 application.yml），
 * 且仅 {@code dev} profile 打开（application-dev.yml 显式置 true）；prod 不装配该调度。
 * 固定间隔（默认 30s）调用 {@link NotificationOutboxService#processDueBatch(int)}，每次输出一行
 * 处理统计，便于 demo 观察 {@code QUEUED → SENT/FAILED} 流转。</p>
 */
@Configuration
@EnableScheduling
@Profile("dev")
@ConditionalOnProperty(name = "aps.notification.scheduler.enabled", havingValue = "true")
public class NotificationOutboxScheduler {

    /**
     * 每批领取上限，与 outbox 服务默认批次一致。
     */
    static final int BATCH_LIMIT = 100;

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxScheduler.class);

    private final NotificationOutboxService outboxService;

    /**
     * 构造轮询驱动。
     *
     * @param outboxService outbox 状态流转服务
     */
    public NotificationOutboxScheduler(NotificationOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * 按固定间隔处理到期记录；只有 {@code status=QUEUED} 且 {@code next_attempt_at <= now} 的行会被领取。
     *
     * @return 本批处理结果（调度忽略返回值，仅为便于单测断言）
     */
    @Scheduled(fixedDelayString = "${aps.notification.scheduler.interval-ms:30000}")
    public NotificationOutboxService.ProcessResult poll() {
        NotificationOutboxService.ProcessResult result = outboxService.processDueBatch(BATCH_LIMIT);
        LOG.info("notification outbox poll: processed={} sent={} retried={} failed={}",
                result.processed(), result.sent(), result.retried(), result.failed());
        return result;
    }
}
