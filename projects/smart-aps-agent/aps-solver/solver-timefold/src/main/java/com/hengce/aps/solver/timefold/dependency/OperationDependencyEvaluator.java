package com.hengce.aps.solver.timefold.dependency;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 工序依赖约束的纯函数实现（contract-freeze C12 §17.2 约束式）。
 *
 * <p>{@code P} 为前序、{@code S} 为当前工序：
 * FS 要求 {@code S.start >= P.end + lag}，SS 要求 {@code S.start >= P.start + lag}，
 * FF 要求 {@code S.end >= P.end + lag}。V1 的 {@code routing_edge.depend_type} 不含 SF，
 * 遇到 SF 必须拒绝整题，不得降级成 FS。</p>
 */
public final class OperationDependencyEvaluator {

    /**
     * 完成-开始。
     */
    public static final String FINISH_START = "FS";

    /**
     * 开始-开始。
     */
    public static final String START_START = "SS";

    /**
     * 完成-完成。
     */
    public static final String FINISH_FINISH = "FF";

    /**
     * 仅保留语义、M1 不支持的类型。
     */
    public static final String START_FINISH = "SF";

    private static final Set<String> SUPPORTED = Set.of(FINISH_START, START_START, FINISH_FINISH);

    private OperationDependencyEvaluator() {
    }

    /**
     * 规范化并校验依赖类型。
     *
     * @param raw 原始依赖类型
     * @return 大写依赖类型
     * @throws IllegalArgumentException 类型为空、SF 或未知取值
     */
    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("工序依赖缺少 depend_type（仅支持 FS/SS/FF）");
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        if (START_FINISH.equals(type)) {
            throw new IllegalArgumentException(
                    "依赖类型 SF 在 V1 不受支持（routing_edge.depend_type 仅 FS/SS/FF），请改用 FS/SS/FF");
        }
        if (!SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("不支持的依赖类型: " + raw);
        }
        return type;
    }

    /**
     * 判断依赖约束是否被违反；任一时间为空视为尚未初始化，不判违反。
     *
     * @param dependType 依赖类型（FS/SS/FF）
     * @param lagMinutes 滞后分钟数
     * @param predecessorStart 前序开始时间
     * @param predecessorEnd 前序结束时间
     * @param successorStart 当前开始时间
     * @param successorEnd 当前结束时间
     * @return {@code true} 表示违反
     */
    public static boolean violated(
            String dependType,
            int lagMinutes,
            LocalDateTime predecessorStart,
            LocalDateTime predecessorEnd,
            LocalDateTime successorStart,
            LocalDateTime successorEnd) {
        if (predecessorStart == null || predecessorEnd == null
                || successorStart == null || successorEnd == null) {
            return false;
        }
        return switch (dependType) {
            case FINISH_START -> successorStart.isBefore(predecessorEnd.plusMinutes(lagMinutes));
            case START_START -> successorStart.isBefore(predecessorStart.plusMinutes(lagMinutes));
            case FINISH_FINISH -> successorEnd.isBefore(predecessorEnd.plusMinutes(lagMinutes));
            default -> throw new IllegalArgumentException("不支持的依赖类型: " + dependType);
        };
    }
}
