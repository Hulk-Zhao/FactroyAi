package com.hengce.aps.service.strategy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略导入导出的 canonical JSON 与 checksum（C19）。
 *
 * <p><b>契约引用</b>：{@code docs/api/contract-freeze.md} 第 24.1 节「checksum 的技术前置（规范化序列化）」。
 * 该节要求 checksum 只有在规范化 JSON 规则写死时才有意义，本类即该规则的唯一实现点。</p>
 *
 * <p><b>canonical JSON 规则（写死，不得在别处另立口径）</b>：</p>
 * <ol>
 *   <li><b>字段顺序</b>：对象键按字典序（{@link String#compareTo}，即 UTF-16 码元序）升序排列，
 *       与 JSON 解析器/数据库的字段顺序无关。</li>
 *   <li><b>null 处理</b>：对象中值为 {@code null} 的键<b>整体省略</b>（因此「键缺省」与「键为 null」
 *       规范化后等价）；数组中的 {@code null} 元素<b>保留</b>为 {@code null}，以保持数组长度与下标。</li>
 *   <li><b>数组顺序</b>：保留原顺序，不排序；{@code rules} 的顺序即导出时的 {@code seq_no} 顺序。</li>
 *   <li><b>数字</b>：统一输出十进制普通记法（无指数、无正号、无尾随零），零固定为 {@code 0}；
 *       因此 {@code 1} 与 {@code 1.0} 规范化后同为 {@code 1}。</li>
 *   <li><b>字符串</b>：最小化 JSON 转义（{@code "}、{@code \\}、控制字符），非 ASCII 字符原样输出为 UTF-8，
 *       不做 {@code \\uXXXX} 全量转义。</li>
 *   <li><b>布尔</b>：{@code true}/{@code false} 字面量。</li>
 *   <li><b>空白</b>：无缩进、无多余空格、无换行；输出按 UTF-8 计算摘要。</li>
 *   <li><b>时间</b>：{@code date} 固定 {@code yyyy-MM-dd}（导出端以字符串写入，本类原样保留）；
 *       {@code date-time} 固定 UTC ISO-8601 带毫秒（同样原样保留，由写入端保证格式）。</li>
 * </ol>
 *
 * <p><b>checksum</b>：对对象 {@code {strategy, rules}} 按上述规则规范化后计算 SHA-256，
 * 输出 <b>64 位小写十六进制</b>。同一份数据两次导出必得同一值；任一业务字段变化必导致值变化。</p>
 */
final class StrategyCanonicalJson {

    private static final int SHA_256_HEX_LENGTH = 64;

    private StrategyCanonicalJson() {
    }

    /**
     * 计算 {@code {strategy, rules}} 的 checksum。
     *
     * @param strategy 策略业务对象（Map）
     * @param rules 规则数组（元素为 Map/List/标量）
     * @return 64 位小写十六进制 SHA-256
     */
    static String checksum(Map<String, Object> strategy, List<?> rules) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("strategy", strategy);
        root.put("rules", rules);
        return sha256(canonicalize(root));
    }

    /**
     * 按 canonical JSON 规则序列化。
     *
     * @param value 支持 Map、Iterable、String、Boolean、Number 及其嵌套
     * @return 无空白 canonical JSON 文本
     */
    static String canonicalize(Object value) {
        StringBuilder out = new StringBuilder();
        appendValue(out, value);
        return out.toString();
    }

    /**
     * 计算 SHA-256 小写十六进制。
     *
     * @param canonical 规范化文本（UTF-8 编码）
     * @return 64 位小写十六进制
     */
    static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(SHA_256_HEX_LENGTH);
            for (byte item : digest) {
                hex.append(Character.forDigit((item >> 4) & 0x0F, 16));
                hex.append(Character.forDigit(item & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            appendObject(out, map);
        } else if (value instanceof Iterable<?> iterable) {
            appendArray(out, iterable);
        } else if (value instanceof String text) {
            appendString(out, text);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Number number) {
            out.append(canonicalNumber(number));
        } else {
            throw new IllegalArgumentException(
                    "canonical JSON 不支持的类型: " + value.getClass().getName());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        List<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("canonical JSON 的对象键必须是字符串: " + key);
            }
            if (map.get(key) != null) {
                keys.add(text);
            }
        }
        keys.sort(Comparator.naturalOrder());
        out.append('{');
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String key = keys.get(index);
            appendString(out, key);
            out.append(':');
            appendValue(out, map.get(key));
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, Iterable<?> values) {
        out.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                out.append(',');
            }
            appendValue(out, value);
            first = false;
        }
        out.append(']');
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20) {
                        out.append("\\u");
                        out.append(String.format("%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String canonicalNumber(Number number) {
        BigDecimal decimal;
        if (number instanceof BigDecimal value) {
            decimal = value;
        } else if (number instanceof BigInteger value) {
            decimal = new BigDecimal(value);
        } else if (number instanceof Integer || number instanceof Long
                || number instanceof Short || number instanceof Byte) {
            decimal = BigDecimal.valueOf(number.longValue());
        } else {
            decimal = new BigDecimal(number.toString());
        }
        if (decimal.signum() == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString();
    }
}
