package com.hengce.aps.service.notification;

import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.OutboxRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 空实现通道：不做任何外部调用，直接视为投递成功。
 *
 * <p>默认启用（{@code aps.notification.sink} 未配置或为 {@code NOOP}），保证 M3 一律不真发。</p>
 */
@Component
@ConditionalOnProperty(name = "aps.notification.sink", havingValue = "NOOP", matchIfMissing = true)
public class NoopNotificationChannelSender implements NotificationChannelSender {

    @Override
    public void send(OutboxRow row) {
        // 故意不发：M3 只验证 outbox 状态流转
    }
}
