package com.hengce.aps.service.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 断言证据记录器：把实际 SQL/行数写入控制台并追加到
 * {@code target/qa-evidence/m1-qa-01-evidence.log}，供测试报告引用。
 */
final class Evidence {

    private static final Path EVIDENCE_FILE = Path.of("target", "qa-evidence", "m1-qa-01-evidence.log");

    private static boolean initialized;

    private Evidence() {
    }

    /**
     * 记录一条证据。
     *
     * @param message 证据内容（建议包含 SQL、参数与查询结果）
     */
    static synchronized void record(String message) {
        String line = "[" + OffsetDateTime.now(ZoneOffset.UTC) + "] " + message;
        System.out.println("[EVIDENCE] " + line);
        initialize();
        try {
            Files.writeString(EVIDENCE_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        try {
            Files.createDirectories(EVIDENCE_FILE.getParent());
            Files.writeString(EVIDENCE_FILE, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            initialized = true;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
