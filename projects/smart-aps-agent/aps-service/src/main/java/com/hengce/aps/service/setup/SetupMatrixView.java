package com.hengce.aps.service.setup;

import com.hengce.aps.infrastructure.persistence.setup.SetupMatrixEntryEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 换型矩阵返回视图（C44）。
 *
 * @param id 主键
 * @param keyType 维度
 * @param fromKey 前序维度键，可空
 * @param toKey 当前维度键，可空
 * @param scopeType TENANT / RESOURCE_GROUP / RESOURCE
 * @param resourceId 资源主键，可空
 * @param resourceGroupId 资源组主键，可空
 * @param setupSeconds 默认换型秒数
 * @param sameSeriesSeconds 同系列特例秒数，可空
 * @param crossSeriesSeconds 跨系列特例秒数，可空
 * @param remark 备注
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record SetupMatrixView(
        UUID id,
        String keyType,
        String fromKey,
        String toKey,
        String scopeType,
        UUID resourceId,
        UUID resourceGroupId,
        Integer setupSeconds,
        Integer sameSeriesSeconds,
        Integer crossSeriesSeconds,
        String remark,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 由持久化实体转换。
     *
     * @param entity 实体
     * @return 返回视图
     */
    public static SetupMatrixView from(SetupMatrixEntryEntity entity) {
        return new SetupMatrixView(
                (UUID) entity.getId(),
                entity.getKeyType(),
                entity.getFromKey(),
                entity.getToKey(),
                entity.getScopeType(),
                entity.getResourceId(),
                entity.getResourceGroupId(),
                entity.getSetupSeconds(),
                entity.getSameSeriesSetupSeconds(),
                entity.getCrossSeriesSetupSeconds(),
                entity.getRemark(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
