package com.hengce.aps.web.controller.process.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 工序模版新建/更新请求体。
 *
 * <p>字段约束对齐 operation_template 表：code 上限 64、name 上限 256，工时非负，
 * templateVersion 缺省为 1。更新场景 {@code version} 由服务层强制要求，用于乐观锁。</p>
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
 * @param templateVersion 业务版本号
 * @param version 乐观锁版本，更新时必填
 */
public record OperationTemplateRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 256) String name,
        UUID primaryResourceId,
        UUID primaryResourceGroupId,
        List<UUID> secondaryResourceIds,
        List<UUID> inputMaterialIds,
        UUID outputMaterialId,
        @Min(0) Integer standardSeconds,
        @Min(0) Integer setupSeconds,
        @Min(0) Integer teardownSeconds,
        @Min(1) Integer templateVersion,
        @Min(1) Integer version) {
}
