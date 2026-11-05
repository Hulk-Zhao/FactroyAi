package com.hengce.aps.solver.api;

import java.util.Map;

/**
 * 算法参数，与具体引擎无关。
 *
 * @param engine 引擎编码：timefold / ortools / rule_based（外部小写，见 C9）
 * @param timeoutSeconds 求解超时（秒），默认 60
 * @param moveThreadCount 移动线程数；0 = 未指定（不设置该配置项，走引擎默认，社区版即单线程），
 *                        &gt;0 = 显式请求，是否生效由引擎适配器按发行版能力决定
 * @param acceptor 接受器类型，默认 LATE_ACCEPTANCE
 * @param randomSeed 随机种子
 * @param maximumStepCount 最大步数，{@code <=0} 表示不限
 * @param unimprovedSecondsSpentLimit 无改进秒数上限，默认 10
 * @param direction 排产方向：FORWARD / BACKWARD / BOTTLENECK
 * @param useFrozenAssignments 是否使用冻结分配，默认 {@code true}
 * @param parameters 请求 parameters 原样透传
 */
public record SolveConfig(
        String engine,
        int timeoutSeconds,
        int moveThreadCount,
        String acceptor,
        long randomSeed,
        long maximumStepCount,
        int unimprovedSecondsSpentLimit,
        String direction,
        boolean useFrozenAssignments,
        Map<String, Object> parameters
) {
}
