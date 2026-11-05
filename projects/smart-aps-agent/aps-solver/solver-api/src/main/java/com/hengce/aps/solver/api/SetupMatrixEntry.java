package com.hengce.aps.solver.api;

import java.util.UUID;

/**
 * 换型矩阵条目（contract-freeze C12 §17.3，C44 扩展）。
 *
 * <p>匹配维度：{@code MATERIAL_CODE}（FROM/TO 产品物料编码）或
 * {@code OPERATION_CODE}（FROM/TO 程序/工序编码）。</p>
 *
 * <p>生效范围：{@code resourceId} 非空为资源级；否则 {@code resourceGroupId} 非空为资源组级；
 * 两者均为空为租户级默认。</p>
 *
 * <p>取值：{@code setupSeconds} 为默认换型秒数；{@code sameSeriesSeconds}/
 * {@code crossSeriesSeconds} 为同系列/跨系列特例，为空表示回退默认值。</p>
 *
 * @param resourceId 资源主键，可空
 * @param resourceGroupId 资源组主键，可空
 * @param keyType 维度：MATERIAL_CODE / OPERATION_CODE
 * @param fromKey 前序维度键，空表示通配
 * @param toKey 当前维度键，空表示通配
 * @param setupSeconds 默认换型秒数
 * @param sameSeriesSeconds 同系列特例秒数，可空
 * @param crossSeriesSeconds 跨系列特例秒数，可空
 */
public record SetupMatrixEntry(
        UUID resourceId,
        UUID resourceGroupId,
        String keyType,
        String fromKey,
        String toKey,
        int setupSeconds,
        Integer sameSeriesSeconds,
        Integer crossSeriesSeconds
) {

    /**
     * 兼容 C44 之前的 5 参数调用：租户级范围、无同/跨系列特例。
     *
     * @param resourceId 资源主键，可空
     * @param keyType 维度
     * @param fromKey 前序维度键，可空
     * @param toKey 当前维度键，可空
     * @param setupSeconds 默认换型秒数
     */
    public SetupMatrixEntry(
            UUID resourceId,
            String keyType,
            String fromKey,
            String toKey,
            int setupSeconds) {
        this(resourceId, null, keyType, fromKey, toKey, setupSeconds, null, null);
    }
}
