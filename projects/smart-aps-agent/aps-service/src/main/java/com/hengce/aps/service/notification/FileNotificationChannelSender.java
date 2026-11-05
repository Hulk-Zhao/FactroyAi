package com.hengce.aps.service.notification;

import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.OutboxRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地文件通道实现：把消息追加到 {@code <dir>/<channel>.jsonl}，不调用任何真实网关。
 *
 * <p>通过 {@code aps.notification.sink=FILE} 启用，默认使用 {@code NOOP}。</p>
 */
@Component
@ConditionalOnProperty(name = "aps.notification.sink", havingValue = "FILE")
public class FileNotificationChannelSender implements NotificationChannelSender {

    private final Path directory;

    /**
     * 构造实现。
     *
     * @param directory 落盘目录
     */
    public FileNotificationChannelSender(@org.springframework.beans.factory.annotation.Value(
            "${aps.notification.outbox-dir:target/notification-outbox}") String directory) {
        this.directory = Path.of(directory);
    }

    @Override
    public void send(OutboxRow row) {
        try {
            Files.createDirectories(directory);
            String line = "{\"dispatchId\":\"" + row.dispatchId()
                    + "\",\"planId\":\"" + row.planId()
                    + "\",\"channel\":\"" + row.channel()
                    + "\",\"address\":\"" + row.address().replace("\"", "\\\"")
                    + "\",\"message\":\"" + escape(row.message()) + "\"}\n";
            Files.writeString(directory.resolve(row.channel() + ".jsonl"), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException("通知落盘失败", exception);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
