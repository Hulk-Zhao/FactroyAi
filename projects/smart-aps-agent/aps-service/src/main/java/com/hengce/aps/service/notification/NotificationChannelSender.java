package com.hengce.aps.service.notification;

import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.OutboxRow;

/**
 * 通知通道 SPI（C33 §25.5.3）。
 *
 * <p>M3 不做真实发送，实现只用于验收 outbox 状态流转：{@code NOOP} 直接成功，
 * {@code FILE} 把消息追加到本地文件。真实短信/邮件网关接入时新增实现即可，不改 outbox 表。</p>
 */
public interface NotificationChannelSender {

    /**
     * 投递一条 outbox 记录。
     *
     * @param row outbox 行
     */
    void send(OutboxRow row);
}
