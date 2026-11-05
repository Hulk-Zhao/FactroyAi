package com.hengce.aps.solver.dsl;

/**
 * DSL 校验错误码，全部落在 422xx 区间（C19）。
 */
public final class DslErrorCodes {

    /**
     * DSL 语法或结构错误。
     */
    public static final String SYNTAX_ERROR = "42201";

    /**
     * 未知规则编码或不支持的键。
     */
    public static final String UNKNOWN_RULE = "42202";

    /**
     * 权重非法（越界或合计不等于 100）。
     */
    public static final String INVALID_WEIGHT = "42203";

    /**
     * 规则参数非法。
     */
    public static final String INVALID_PARAM = "42204";

    /**
     * 文档超出长度或层级限制。
     */
    public static final String LIMIT_EXCEEDED = "42205";

    /**
     * 规则重复声明。
     */
    public static final String DUPLICATE_RULE = "42206";

    /**
     * 规则缺失或数量不符。
     */
    public static final String MISSING_RULE = "42207";

    private DslErrorCodes() {
    }
}
