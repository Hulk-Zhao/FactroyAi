package com.hengce.aps.web.controller.process.dto;

import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 工序模版响应体，只暴露对外可见字段。
 *
 * @param id 主键
 * @param code 模版编码
 * @param name 模版名称
 * @param primaryResourceId 主资源
 * @param primaryResourceGroupId 主资源组
 * @param secondaryResourceIds 辅助资源数组
 * @param inputMaterialIds 输入物料数组
 * @param outputMaterialId 产出物料
 * @param standardSeconds 单件标准工时（秒）
 * @param setupSeconds 换型工时（秒）
 * @param teardownSeconds 收尾工时（秒）
 * @param templateVersion 业务版本号
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record OperationTemplateView(
        UUID id,
        String code,
        String name,
        UUID primaryResourceId,
        UUID primaryResourceGroupId,
        List<UUID> secondaryResourceIds,
        List<UUID> inputMaterialIds,
        UUID outputMaterialId,
        Integer standardSeconds,
        Integer setupSeconds,
        Integer teardownSeconds,
        Integer templateVersion,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 由领域实体转换。
     *
     * @param entity 工序模版实体
     * @return 响应体
     */
    public static OperationTemplateView from(OperationTemplate entity) {
        return new OperationTemplateView(
                (UUID) entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getPrimaryResourceId(),
                entity.getPrimaryResourceGroupId(),
                entity.getSecondaryResourceIds() == null ? List.of() : entity.getSecondaryResourceIds(),
                entity.getInputMaterialIds() == null ? List.of() : entity.getInputMaterialIds(),
                entity.getOutputMaterialId(),
                entity.getStandardSeconds(),
                entity.getSetupSeconds(),
                entity.getTeardownSeconds(),
                entity.getTemplateVersion(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
