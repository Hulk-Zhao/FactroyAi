package com.hengce.aps.solver.api.task;

/**
 * C37 §25.8 重试口径：仅三类引擎侧失败可重试。
 *
 * <p>{@code ENGINE_ERROR} / {@code TIMEOUT} / {@code SOLVER_UNAVAILABLE} 由
 * {@code metrics.engineFallbackReason} 表达；DSL/参数校验失败、重叠守卫、
 * 覆盖不完整属业务结果，不在本集合内。</p>
 *
 * <p><b>A0</b>：{@link #SOLVER_BUSY} 表示“求解槽位被占满且等待超时”，属于容量拒绝，
 * <b>明确不可重试</b>——重试只会把等待与 30 秒退避叠加成重试风暴。</p>
 */
public final class SolveRetryReasons {

    /**
     * 引擎异常。
     */
    public static final String ENGINE_ERROR = "ENGINE_ERROR";

    /**
     * 引擎超时。
     */
    public static final String TIMEOUT = "TIMEOUT";

    /**
     * 引擎未装配 / 容量不可用。
     */
    public static final String SOLVER_UNAVAILABLE = "SOLVER_UNAVAILABLE";

    /**
     * 求解槽位等待超时（A0）：不可重试。
     */
    public static final String SOLVER_BUSY = "SOLVER_BUSY";

    private SolveRetryReasons() {
    }

    /**
     * 判断失败原因是否属于可重试集合。
     *
     * <p>{@link #SOLVER_BUSY} 是容量拒绝，不在此集合内。</p>
     *
     * @param reason {@code engineFallbackReason}
     * @return 属于三类可重试原因时为 true
     */
    public static boolean isRetryable(String reason) {
        return ENGINE_ERROR.equals(reason)
                || TIMEOUT.equals(reason)
                || SOLVER_UNAVAILABLE.equals(reason);
    }
}
