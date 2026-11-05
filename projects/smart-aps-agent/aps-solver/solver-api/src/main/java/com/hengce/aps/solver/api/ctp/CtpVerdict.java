package com.hengce.aps.solver.api.ctp;

/**
 * C53 CTP 询单结论。
 *
 * <p>只有 {@code PROMISED} 允许返回承诺日期/数量；{@code UNPROMISABLE} 表示预算内未找到
 * 可行解，不是不可行证明。{@code UNVERIFIED} 用于“物料侧已判定可齐、但容量尚未由求解器验证”
 * 的中间态：此时不得返回任何承诺日期。</p>
 */
public enum CtpVerdict {
    PROMISED,
    UNPROMISABLE,
    OUT_OF_HORIZON,
    NO_ROUTE,
    NO_MATERIAL,
    SOLVER_TIMEOUT,
    BUSY,

    /** 物料可齐但容量未验证：只由物料侧车道返回，承接 C2 容量验证。 */
    UNVERIFIED
}
