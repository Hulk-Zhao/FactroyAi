package com.hengce.aps.solver.timefold.setup;

import com.hengce.aps.solver.api.SetupMatrixEntry;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 换型矩阵解析（contract-freeze C12 §17.3.2，优先级由 C44 扩展）。
 *
 * <p>条目优先级从高到低：</p>
 * <ol>
 *   <li>键精确（FROM/TO 都命中）高于单边命中，高于通配默认；即“特例 &gt; 默认”。</li>
 *   <li>相同键精度下：资源级 &gt; 资源组级 &gt; 租户级。</li>
 *   <li>完全相同精度时取较大换型秒数（沿用 C12 的安全兜底）。</li>
 * </ol>
 *
 * <p>胜出条目再按系列取值：产品族相同且非空时取 {@code sameSeriesSeconds}，
 * 否则（跨系列或产品族缺失）取 {@code crossSeriesSeconds}；特例为空回退
 * {@code setupSeconds}。产品族缺失时按跨系列处理，避免在无法证明同系列时使用更短的
 * 同系列工时导致排产低估——“跨系列 &gt; 同系列”即体现在这里。</p>
 */
public final class SetupMatrixResolver {

    /**
     * 按工序编码匹配。
     */
    public static final String KEY_OPERATION_CODE = "OPERATION_CODE";

    /**
     * 按物料编码匹配。
     */
    public static final String KEY_MATERIAL_CODE = "MATERIAL_CODE";

    private static final int KEY_PRECISION_WEIGHT = 10;

    private SetupMatrixResolver() {
    }

    /**
     * 解析相邻任务的换型秒数（无资源组/产品族信息时的兼容入口）。
     *
     * @param matrix 换型矩阵
     * @param resourceId 当前任务资源
     * @param operationCode 当前任务工序编码
     * @param materialCode 当前任务物料编码
     * @param previousOperationCode 前序任务工序编码
     * @param previousMaterialCode 前序任务物料编码
     * @return 命中的换型秒数；未命中返回 {@code null}，由调用方回退静态值
     */
    public static Long resolve(
            List<SetupMatrixEntry> matrix,
            UUID resourceId,
            String operationCode,
            String materialCode,
            String previousOperationCode,
            String previousMaterialCode) {
        return resolve(matrix, resourceId, null, operationCode, materialCode, null,
                previousOperationCode, previousMaterialCode, null);
    }

    /**
     * 解析相邻任务的换型秒数，含资源组与产品族（C44）。
     *
     * @param matrix 换型矩阵
     * @param resourceId 当前任务资源
     * @param resourceGroupId 当前任务资源组，可空
     * @param operationCode 当前任务工序编码
     * @param materialCode 当前任务物料编码
     * @param productFamily 当前任务产品族，可空
     * @param previousOperationCode 前序任务工序编码
     * @param previousMaterialCode 前序任务物料编码
     * @param previousProductFamily 前序任务产品族，可空
     * @return 命中的换型秒数；未命中返回 {@code null}，由调用方回退静态值
     */
    public static Long resolve(
            List<SetupMatrixEntry> matrix,
            UUID resourceId,
            UUID resourceGroupId,
            String operationCode,
            String materialCode,
            String productFamily,
            String previousOperationCode,
            String previousMaterialCode,
            String previousProductFamily) {
        if (matrix == null || matrix.isEmpty()) {
            return null;
        }
        boolean sameSeries = isSameSeries(productFamily, previousProductFamily);
        long bestRank = -1;
        Long bestSetup = null;
        for (SetupMatrixEntry entry : matrix) {
            validate(entry);
            if (entry.resourceId() != null && !entry.resourceId().equals(resourceId)) {
                continue;
            }
            if (entry.resourceGroupId() != null && !entry.resourceGroupId().equals(resourceGroupId)) {
                continue;
            }
            String keyType = entry.keyType() == null
                    ? "" : entry.keyType().trim().toUpperCase(Locale.ROOT);
            String fromKey;
            String toKey;
            if (KEY_OPERATION_CODE.equals(keyType)) {
                fromKey = previousOperationCode;
                toKey = operationCode;
            } else if (KEY_MATERIAL_CODE.equals(keyType)) {
                fromKey = previousMaterialCode;
                toKey = materialCode;
            } else {
                continue;
            }
            if (!matches(entry.fromKey(), fromKey) || !matches(entry.toKey(), toKey)) {
                continue;
            }
            long rank = keyRank(entry) * KEY_PRECISION_WEIGHT + scopeRank(entry);
            long setup = seriesSetup(entry, sameSeries);
            if (rank > bestRank
                    || (rank == bestRank && bestSetup != null && setup > bestSetup)) {
                bestRank = rank;
                bestSetup = setup;
            }
        }
        return bestSetup;
    }

    private static void validate(SetupMatrixEntry entry) {
        if (entry.setupSeconds() < 0) {
            throw new IllegalArgumentException("换型矩阵的 setupSeconds 不能为负数: " + entry);
        }
        if (entry.sameSeriesSeconds() != null && entry.sameSeriesSeconds() < 0) {
            throw new IllegalArgumentException("换型矩阵的 sameSeriesSeconds 不能为负数: " + entry);
        }
        if (entry.crossSeriesSeconds() != null && entry.crossSeriesSeconds() < 0) {
            throw new IllegalArgumentException("换型矩阵的 crossSeriesSeconds 不能为负数: " + entry);
        }
    }

    private static long keyRank(SetupMatrixEntry entry) {
        int precision = 0;
        if (entry.fromKey() != null) {
            precision++;
        }
        if (entry.toKey() != null) {
            precision++;
        }
        return precision;
    }

    private static long scopeRank(SetupMatrixEntry entry) {
        if (entry.resourceId() != null) {
            return 2;
        }
        if (entry.resourceGroupId() != null) {
            return 1;
        }
        return 0;
    }

    private static long seriesSetup(SetupMatrixEntry entry, boolean sameSeries) {
        Integer specific = sameSeries ? entry.sameSeriesSeconds() : entry.crossSeriesSeconds();
        return specific == null ? (long) entry.setupSeconds() : specific;
    }

    private static boolean isSameSeries(String productFamily, String previousProductFamily) {
        String current = normalized(productFamily);
        String previous = normalized(previousProductFamily);
        return current != null && previous != null && Objects.equals(current, previous);
    }

    private static boolean matches(String key, String actual) {
        return key == null || key.equals(actual);
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
