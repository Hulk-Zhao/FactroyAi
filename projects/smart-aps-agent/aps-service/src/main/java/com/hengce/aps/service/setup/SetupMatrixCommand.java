package com.hengce.aps.service.setup;

import java.util.UUID;

/**
 * 换型矩阵创建/更新命令（C44）。
 *
 * @param keyType 维度：PRODUCT/MATERIAL_CODE 或 PROGRAM/OPERATION_CODE
 * @param fromKey 前序维度键；空表示通配默认
 * @param toKey 当前维度键；空表示通配默认
 * @param resourceId 资源主键；与 resourceGroupId 最多一个非空
 * @param resourceGroupId 资源组主键；与 resourceId 最多一个非空
 * @param setupSeconds 默认换型秒数
 * @param sameSeriesSeconds 同系列特例秒数，可空
 * @param crossSeriesSeconds 跨系列特例秒数，可空
 * @param remark 备注
 * @param version 乐观锁版本；创建时忽略，更新时必填
 */
public record SetupMatrixCommand(
        String keyType,
        String fromKey,
        String toKey,
        UUID resourceId,
        UUID resourceGroupId,
        Integer setupSeconds,
        Integer sameSeriesSeconds,
        Integer crossSeriesSeconds,
        String remark,
        Integer version) {
}
