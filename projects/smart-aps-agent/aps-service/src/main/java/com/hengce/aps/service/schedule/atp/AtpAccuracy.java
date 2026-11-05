package com.hengce.aps.service.schedule.atp;

/**
 * ATP 结论的准确性口径，必须在响应中显式返回。
 *
 * <p>{@code ESTIMATE} = 构造启发式估算（FAST 固定为该值）；
 * {@code VERIFIED} = 限时求解拿到完整可行解后的日期；
 * {@code UNVERIFIED} = 超时/不可行/引擎失败，未形成可信日期。</p>
 */
public enum AtpAccuracy {

    /**
     * 估算：只来自构造启发式。
     */
    ESTIMATE,

    /**
     * 已验证：来自完整可行解的排程结果。
     */
    VERIFIED,

    /**
     * 未验证：没有可信日期。
     */
    UNVERIFIED
}
