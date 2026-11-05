package com.hengce.aps.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 软约束权重配置的稳定哈希（A0，供基准报告绑定“当时用的是哪套权重”）。
 *
 * <p>规范形式固定为：{@code soft-weights/v1:CODE=WEIGHT&CODE=WEIGHT...}，
 * 规则编码按字典序排序，因此同一套权重无论入参 {@link Map} 的顺序如何，
 * 哈希都相同；空映射（仅硬约束）也有稳定哈希。算法为 SHA-256。</p>
 */
public final class SoftWeightConfigHasher {

    /**
     * 规范形式前缀；任何编码/排序规则调整都必须升级版本号。
     */
    private static final String PREFIX = "soft-weights/v1:";

    private SoftWeightConfigHasher() {
    }

    /**
     * 计算软权重配置哈希。
     *
     * @param weights 规则编码到权重；{@code null} 视同空映射
     * @return {@code sha256:<64 位小写十六进制>}
     */
    public static String hash(Map<String, Integer> weights) {
        return "sha256:" + HexFormat.of().formatHex(
                digest(canonical(weights).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 输出规范形式，便于报告里人工核对哈希来源。
     *
     * @param weights 规则编码到权重；{@code null} 视同空映射
     * @return 规范字符串
     */
    public static String canonical(Map<String, Integer> weights) {
        StringBuilder builder = new StringBuilder(PREFIX);
        boolean first = true;
        for (Map.Entry<String, Integer> entry : sorted(weights).entrySet()) {
            if (!first) {
                builder.append('&');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return builder.toString();
    }

    /**
     * 按编码字典序复制，保证跨进程/跨 JDK 的规范形式一致。
     *
     * @param weights 原始权重
     * @return 有序副本
     */
    private static Map<String, Integer> sorted(Map<String, Integer> weights) {
        Map<String, Integer> sorted = new TreeMap<>();
        if (weights != null) {
            weights.forEach((code, weight) -> {
                if (code != null) {
                    sorted.put(code, weight == null ? 0 : weight);
                }
            });
        }
        return sorted;
    }

    /**
     * 计算 SHA-256；JDK 必然提供该算法，异常降级为 IllegalStateException。
     *
     * @param payload 待摘要字节
     * @return 摘要
     */
    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
