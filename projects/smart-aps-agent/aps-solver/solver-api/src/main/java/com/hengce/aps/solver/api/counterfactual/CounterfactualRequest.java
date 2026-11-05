package com.hengce.aps.solver.api.counterfactual;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C52 反事实验证入参。
 *
 * <p>{@code params} 的必填键由 {@link CounterfactualKind} 决定；未知键忽略。
 * 只读重解，不写计划。</p>
 *
 * @param kind 修复类型
 * @param params kind 相关参数
 * @param timeoutSeconds 单次限时秒数，1..10，缺省 5
 */
public record CounterfactualRequest(
        CounterfactualKind kind,
        Map<String, Object> params,
        Integer timeoutSeconds
) {

    public CounterfactualRequest {
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        timeoutSeconds = timeoutSeconds == null ? 5 : timeoutSeconds;
    }
}
