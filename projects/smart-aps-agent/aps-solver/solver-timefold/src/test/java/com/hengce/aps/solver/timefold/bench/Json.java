package com.hengce.aps.solver.timefold.bench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基准跑分用的极简 JSON 解析/序列化工具。
 *
 * <p>只依赖 JDK：求解模块的测试依赖里没有 Jackson 运行时，且 A3 车道不允许改 pom，
 * 因此题面固化与报告输出都用本类。支持标准 JSON（对象/数组/字符串/整数/小数/布尔/null），
 * {@link #canonical(Object)} 对键排序，保证同内容得到同 SHA256。</p>
 */
final class Json {

    private Json() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @param text JSON 文本
     * @return Map / List / String / Long / Double / Boolean / null
     */
    static Object parse(String text) {
        return new Parser(text).parseDocument();
    }

    /**
     * 序列化为紧凑 JSON。
     *
     * @param value 值
     * @return JSON 文本
     */
    static String write(Object value) {
        StringBuilder builder = new StringBuilder(256);
        writeValue(value, builder, false);
        return builder.toString();
    }

    /**
     * 序列化为键有序的紧凑 JSON，用于计算稳定指纹。
     *
     * @param value 值
     * @return 规范化 JSON 文本
     */
    static String canonical(Object value) {
        StringBuilder builder = new StringBuilder(256);
        writeValue(value, builder, true);
        return builder.toString();
    }

    /**
     * SHA256 十六进制摘要。
     *
     * @param text 文本
     * @return 64 位小写十六进制
     */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("期望 JSON 对象，实际: " + value);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("期望 JSON 数组，实际: " + value);
        }
        return (List<Object>) list;
    }

    static String str(Map<String, Object> map, String key, String defaultValue) {
        Object raw = map.get(key);
        return raw == null ? defaultValue : String.valueOf(raw);
    }

    static int intValue(Map<String, Object> map, String key, int defaultValue) {
        Object raw = map.get(key);
        return raw instanceof Number number ? number.intValue() : defaultValue;
    }

    static long longValue(Map<String, Object> map, String key, long defaultValue) {
        Object raw = map.get(key);
        return raw instanceof Number number ? number.longValue() : defaultValue;
    }

    static boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object raw = map.get(key);
        return raw instanceof Boolean flag ? flag : defaultValue;
    }

    static List<Integer> intList(Map<String, Object> map, String key, List<Integer> defaultValue) {
        Object raw = map.get(key);
        if (!(raw instanceof List<?> list)) {
            return defaultValue;
        }
        List<Integer> values = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number number) {
                values.add(number.intValue());
            }
        }
        return values;
    }

    static List<String> stringList(Map<String, Object> map, String key, List<String> defaultValue) {
        Object raw = map.get(key);
        if (!(raw instanceof List<?> list)) {
            return defaultValue;
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder builder, boolean sorted) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(text, builder);
        } else if (value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short) {
            builder.append(value);
        } else if (value instanceof Number number) {
            builder.append(number);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = sorted
                    ? new TreeMap<>((Map<String, Object>) map)
                    : new LinkedHashMap<>((Map<String, Object>) map);
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(entry.getKey(), builder);
                builder.append(':');
                writeValue(entry.getValue(), builder, sorted);
            }
            builder.append('}');
        } else if (value instanceof List<?> list) {
            builder.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                writeValue(list.get(index), builder, sorted);
            }
            builder.append(']');
        } else {
            throw new IllegalArgumentException("无法序列化的类型: " + value.getClass());
        }
    }

    private static void writeString(String text, StringBuilder builder) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {

        private final String text;

        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private Object parseDocument() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (position < text.length()) {
                throw error("文档结束后仍有多余字符");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (position >= text.length()) {
                throw error("意外结束");
            }
            char ch = text.charAt(position);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char ch = peek();
                position++;
                if (ch == '}') {
                    return map;
                }
                if (ch != ',') {
                    throw error("对象缺少 ',' 或 '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            position++;
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char ch = peek();
                position++;
                if (ch == ']') {
                    return list;
                }
                if (ch != ',') {
                    throw error("数组缺少 ',' 或 ']'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < text.length()) {
                char ch = text.charAt(position++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch != '\\') {
                    builder.append(ch);
                    continue;
                }
                if (position >= text.length()) {
                    throw error("字符串转义未结束");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("\\u 转义不完整");
                        }
                        builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw error("非法转义: \\" + escape);
                }
            }
            throw error("字符串未闭合");
        }

        private Object parseNumber() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            boolean decimal = false;
            while (position < text.length()) {
                char ch = text.charAt(position);
                if (ch >= '0' && ch <= '9') {
                    position++;
                } else if (ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                    decimal = true;
                    position++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, position);
            if (raw.isEmpty() || "-".equals(raw)) {
                throw error("非法数字");
            }
            return decimal ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, position)) {
                throw error("非法字面量");
            }
            position += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != expected) {
                throw error("期望 '" + expected + "'");
            }
            position++;
        }

        private char peek() {
            if (position >= text.length()) {
                throw error("意外结束");
            }
            return text.charAt(position);
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON 解析失败 @" + position + ": " + message);
        }
    }
}
