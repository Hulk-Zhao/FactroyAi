package com.hengce.aps.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具：对外统一 ISO-8601（UTC），内部按 {@link ZoneId} 转换。
 *
 * <p>约定见 {@code docs/api/conventions.md}：接口时间一律带时区（{@code 2026-11-04T08:00:00Z}），
 * 数据库使用 TIMESTAMPTZ 存储 UTC。</p>
 */
public final class TimeUtils {

    /** UTC 时区。 */
    public static final ZoneId UTC = ZoneOffset.UTC;

    /** 默认展示时区（Asia/Shanghai）。 */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private TimeUtils() {
    }

    /**
     * 格式化时间点。
     *
     * @param instant 时间点
     * @return ISO-8601 UTC 字符串
     */
    public static String format(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /**
     * 格式化带偏移时间。
     *
     * @param offsetDateTime 带偏移时间
     * @return ISO-8601 字符串
     */
    public static String format(OffsetDateTime offsetDateTime) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetDateTime);
    }

    /**
     * 解析 ISO-8601 时间点。
     *
     * @param text 文本
     * @return 时间点
     */
    public static Instant parseInstant(String text) {
        return Instant.parse(text);
    }

    /**
     * 本地时间按指定时区转为 UTC 时间点。
     *
     * @param localDateTime 本地时间
     * @param zone 本地时区
     * @return UTC 时间点
     */
    public static Instant toUtc(LocalDateTime localDateTime, ZoneId zone) {
        return localDateTime.atZone(zone).toInstant();
    }
}
