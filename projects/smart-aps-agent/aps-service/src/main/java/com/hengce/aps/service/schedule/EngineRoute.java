package com.hengce.aps.service.schedule;

import com.hengce.aps.solver.api.SolverEngine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 引擎路由决策结果（M2-SE-06，C28）。
 *
 * <p>路由来源固定为 {@code REQUEST > STRATEGY > DEFAULT}；{@code effectiveEngine} 是实际执行的引擎，
 * 当目标引擎（当前仅 {@code ortools}）不可用时收敛到 {@link SolverEngine#TIMEFOLD} 并置降级标记。</p>
 *
 * @param requestedEngine 请求或策略声明的引擎编码（小写）；缺省时为默认引擎编码
 * @param engineSource 决策来源：REQUEST / STRATEGY / DEFAULT
 * @param selectedEngine 解析后的目标引擎
 * @param effectiveEngine 实际执行的引擎
 * @param fallback 是否发生引擎降级
 * @param fallbackReason 降级原因（C28 枚举：TIMEOUT/ENGINE_ERROR/SOLVER_UNAVAILABLE）
 * @param fallbackDetail 降级细分（用于区分配置问题与容量问题；C28 未冻结该键，属本卡补充）
 * @param note 降级说明
 */
public record EngineRoute(
        String requestedEngine,
        String engineSource,
        SolverEngine selectedEngine,
        SolverEngine effectiveEngine,
        boolean fallback,
        String fallbackReason,
        String fallbackDetail,
        String note) {

    /** 决策来源：请求显式指定。 */
    public static final String SOURCE_REQUEST = "REQUEST";

    /** 决策来源：策略声明。 */
    public static final String SOURCE_STRATEGY = "STRATEGY";

    /** 决策来源：系统默认。 */
    public static final String SOURCE_DEFAULT = "DEFAULT";

    /** 降级模式：M2 唯一取值。 */
    public static final String FALLBACK_MODE = "HARD_ONLY";

    /** 降级原因：目标引擎不可用。 */
    public static final String REASON_SOLVER_UNAVAILABLE = "SOLVER_UNAVAILABLE";

    /** 降级细分：OR-Tools 未启用/未实现（配置问题）。 */
    public static final String DETAIL_ORTOOLS_NOT_ENABLED = "ORTOOLS_NOT_ENABLED";

    /** 降级细分：求解锁争用等待超时（容量问题）。 */
    public static final String DETAIL_LOCK_CONTENTION = "LOCK_CONTENTION";

    /** 降级细分：引擎执行异常。 */
    public static final String DETAIL_ENGINE_EXCEPTION = "ENGINE_EXCEPTION";

    /** 降级细分：引擎未装配。 */
    public static final String DETAIL_SOLVER_NOT_ASSEMBLED = "SOLVER_NOT_ASSEMBLED";

    /** 降级细分：求解超时。 */
    public static final String DETAIL_SOLVER_TIMEOUT = "SOLVER_TIMEOUT";

    /**
     * 写入路由决策指标（不含降级标记），用于解释“按谁的意愿选了哪个引擎”。
     *
     * @param metrics 求解指标
     */
    public void applyDecision(Map<String, Object> metrics) {
        metrics.putIfAbsent("requestedEngine", requestedEngine);
        metrics.putIfAbsent("engineSource", engineSource);
        metrics.putIfAbsent("engineSpecified", !SOURCE_DEFAULT.equals(engineSource));
        metrics.putIfAbsent("selectedEngine", selectedEngine.getCode());
        metrics.putIfAbsent("effectiveEngine", effectiveEngine.getCode());
    }

    /**
     * 写入路由决策与降级标记；已存在的键优先，避免覆盖执行阶段得到的真实失败原因。
     *
     * @param metrics 求解指标
     */
    public void applyFallback(Map<String, Object> metrics) {
        applyDecision(metrics);
        if (!fallback) {
            return;
        }
        metrics.putIfAbsent("engineFallback", true);
        metrics.putIfAbsent("engineFallbackReason", fallbackReason);
        if (fallbackDetail != null) {
            metrics.putIfAbsent("engineFallbackDetail", fallbackDetail);
        }
        metrics.putIfAbsent("fallbackMode", FALLBACK_MODE);
        metrics.putIfAbsent("fallbackAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }
}
