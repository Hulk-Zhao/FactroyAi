package com.hengce.aps.service.audit;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计快照脱敏工具（C31 / 25.2.2）。
 *
 * <p>敏感键（不区分大小写，忽略 {@code _}/{@code -} 分隔符）在写入前替换为 {@code [REDACTED]}；
 * 嵌套 Map/List/Set/数组递归处理，JSON 字符串按 {@code "key": value} 模式脱敏。
 * 返回新对象，不修改调用方传入的 Map。</p>
 */
public final class AuditValues {

    /**
     * 需要脱敏的键（统一小写且去掉分隔符后的形式）。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwordhash", "token", "accesstoken", "refreshtoken", "secret", "secretkey",
            "apikey", "authorization", "credential", "credentials", "privatekey", "accesskey",
            "secretaccesskey");

    /**
     * JSON 字符串中的敏感键值对；只替换标量值，保留其余结构。
     */
    private static final Pattern JSON_SENSITIVE_PAIR = Pattern.compile(
            "(\"(?:password|password[_-]?hash|token|access[_-]?token|refresh[_-]?token|secret|"
                    + "secret[_-]?key|api[_-]?key|authorization|credential|private[_-]?key|access[_-]?key|"
                    + "secret[_-]?access[_-]?key)\"\\s*:\\s*)"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|null|true|false|-?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 脱敏占位符。
     */
    public static final String REDACTED = "[REDACTED]";

    private AuditValues() {
    }

    /**
     * 递归脱敏并返回副本。
     *
     * @param value 原始快照，可为 null
     * @return 脱敏后的副本；入参为 null 时返回 null
     */
    public static Map<String, Object> redact(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (isSensitive(key)) {
                result.put(key, REDACTED);
            } else {
                result.put(key, redactValue(item));
            }
        });
        return result;
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String textKey = String.valueOf(key);
                nested.put(textKey, isSensitive(textKey) ? REDACTED : redactValue(item));
            });
            return nested;
        }
        if (value instanceof Collection<?> collection) {
            Collection<Object> nested = value instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object item : collection) {
                nested.add(redactValue(item));
            }
            return nested;
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> nested = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                nested.add(redactValue(Array.get(value, index)));
            }
            return nested;
        }
        if (value instanceof String text) {
            return redactJson(text);
        }
        return value;
    }

    /**
     * 对 JSON 文本中的敏感键值对做脱敏；非 JSON 文本原样返回。
     *
     * @param text 原始字符串
     * @return 脱敏后的字符串
     */
    private static String redactJson(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return text;
        }
        Matcher matcher = JSON_SENSITIVE_PAIR.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + "\"" + REDACTED + "\""));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEYS.contains(normalize(key));
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
