package com.hengce.aps.solver.api;

import com.hengce.aps.common.constant.ApsConstants;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 过渡信封 {@link SolveRequest}/{@link SolveResult} 与中立 DTO 的双向映射（contract-freeze C1 §3.5）。
 *
 * <p>本类只做字段映射，不查询数据库、不组装订单/资源明细；问题快照由服务层在 S2 阶段补齐。</p>
 */
public final class SolveEnvelopeMapper {

    /**
     * 默认求解超时（秒）。
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * 默认无改进上限（秒）。
     */
    private static final int DEFAULT_UNIMPROVED_SECONDS = 10;

    /**
     * 默认接受器。
     */
    private static final String DEFAULT_ACCEPTOR = "LATE_ACCEPTANCE";

    /**
     * 默认排产方向。
     */
    private static final String DEFAULT_DIRECTION = "FORWARD";

    private SolveEnvelopeMapper() {
    }

    /**
     * 将 HTTP 请求映射为引擎中立问题。M1 仅落默认场景/租户与信封字段，
     * 订单、资源、冻结分配与约束由服务层后续组装。
     *
     * @param request 求解请求
     * @param config 求解配置
     * @return 中立问题
     */
    public static ScheduleProblem toProblem(SolveRequest request, SolveConfig config) {
        return new ScheduleProblem(
                ApsConstants.DEFAULT_TENANT_ID,
                request.sandboxId() == null ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId(),
                null,
                request.strategyId(),
                config.direction(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * 将 HTTP 请求映射为引擎中立配置；已知参数提升为强类型字段，其余原样透传。
     *
     * @param request 求解请求
     * @param engine 已解析的引擎
     * @return 中立配置
     */
    public static SolveConfig toConfig(SolveRequest request, SolverEngine engine) {
        Map<String, Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
        // 未传/0 一律保持 0 语义（= 不设置 moveThreadCount，走引擎默认），
        // 不再在入口处替调用方“补”成 CPU-1；是否多线程由适配器按发行版能力决定。
        int moveThreadCount = Math.max(0, intParameter(parameters, "moveThreadCount", 0));
        return new SolveConfig(
                engine.getCode(),
                intParameter(parameters, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS),
                moveThreadCount,
                stringParameter(parameters, "acceptor", DEFAULT_ACCEPTOR),
                longParameter(parameters, "randomSeed", 0L),
                longParameter(parameters, "maxSteps", 0L),
                intParameter(parameters, "unimprovedSecondsSpentLimit", DEFAULT_UNIMPROVED_SECONDS),
                stringParameter(parameters, "direction", DEFAULT_DIRECTION).toUpperCase(Locale.ROOT),
                booleanParameter(parameters, "useFrozenAssignments", true),
                parameters);
    }

    /**
     * 将中立求解结果映射回 HTTP 过渡信封。
     *
     * @param request 原始请求，用于回显 {@code taskNo}
     * @param solution 中立求解结果，可为 {@code null}
     * @return HTTP 过渡结果
     */
    public static SolveResult toResult(SolveRequest request, ScheduleSolution solution) {
        if (solution == null) {
            return new SolveResult(request.taskNo(), SolveStatus.FAILED, List.of(), "求解器未返回结果");
        }
        List<ScheduledTask> tasks = solution.assignments().stream()
                .map(assignment -> new ScheduledTask(
                        assignment.orderNo(),
                        assignment.resourceCode(),
                        assignment.startTime(),
                        assignment.endTime()))
                .toList();
        return new SolveResult(
                request.taskNo(),
                toLegacyStatus(solution.status()),
                tasks,
                solution.message());
    }

    private static SolveStatus toLegacyStatus(String status) {
        if (status == null) {
            return SolveStatus.FAILED;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "SOLVED" -> SolveStatus.SUCCESS;
            case "FAILED", "INFEASIBLE", "TIMEOUT" -> SolveStatus.FAILED;
            default -> SolveStatus.FAILED;
        };
    }

    private static int intParameter(Map<String, Object> parameters, String key, int fallback) {
        Object value = parameters.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longParameter(Map<String, Object> parameters, String key, long fallback) {
        Object value = parameters.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String stringParameter(Map<String, Object> parameters, String key, String fallback) {
        Object value = parameters.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static boolean booleanParameter(Map<String, Object> parameters, String key, boolean fallback) {
        Object value = parameters.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }
}
