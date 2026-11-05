package com.hengce.aps.service.process.template;

import java.util.List;
import java.util.UUID;

/**
 * 工序模版写入命令，由 Web 层把请求体映射为领域无关的参数对象。
 *
 * <p>{@code version} 仅更新场景必填，用于乐观锁校验；新增场景忽略该字段。</p>
 *
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
 * @param templateVersion 业务版本号，空表示默认 1
 * @param version 乐观锁版本，更新时必填
 */
public record OperationTemplateCommand(
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
        Integer version) {
}
