package com.hengce.aps.service.schedule.atp;

/**
 * ATP 交期预估档位。
 *
 * <p>{@code FAST} 使用构造启发式，未运行求解器，结论只能是估算；
 * {@code ACCURATE} 使用 Timefold 限时求解，只有拿到完整可行解时才标注为已验证。</p>
 */
public enum AtpMode {

    /**
     * 快速估算：构造启发式，不运行完整求解器。
     */
    FAST,

    /**
     * 精确档：限时求解，超时且未得到完整可行解时不给日期。
     */
    ACCURATE;

    /**
     * 解析档位编码；空值按 {@code FAST}。
     *
     * @param code 档位编码，大小写不敏感
     * @return 档位
     * @throws IllegalArgumentException 编码不在 FAST/ACCURATE 范围
     */
    public static AtpMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return FAST;
        }
        for (AtpMode mode : values()) {
            if (mode.name().equalsIgnoreCase(code.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的 ATP 档位: " + code);
    }
}
