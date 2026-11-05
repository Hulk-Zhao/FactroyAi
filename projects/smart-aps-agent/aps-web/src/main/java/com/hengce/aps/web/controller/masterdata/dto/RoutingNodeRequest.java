package com.hengce.aps.web.controller.masterdata.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工艺路线节点请求体。
 *
 * <p>资源使用 UUID（契约 C15）；{@code resourceCode} 仅为旧占位兼容字段，
 * 只传 code 未传 {@code primaryResourceId} 时服务层返回 42200。</p>
 *
 * @param seqNo 节点序号，路线内唯一
 * @param operationTemplateId 工序模版 UUID
 * @param operationCode 工序编码
 * @param operationName 工序名称
 * @param standardSeconds 标准加工秒数
 * @param setupSeconds 换型秒数
 * @param primaryResourceMode 主资源模式 INHERIT/FLOAT/FIXED，缺省 INHERIT
 * @param primaryResourceId 主资源 UUID
 * @param resourceCode 旧资源编码字段（废弃）
 * @param secondaryResourceIds 辅助资源 UUID 列表
 * @param optional 是否可选工序
 * @param industryAttrs 行业扩展属性（当前不落库）
 */
public record RoutingNodeRequest(
        @NotNull @Min(0) Integer seqNo,
        UUID operationTemplateId,
        @Size(max = 64) String operationCode,
        @Size(max = 128) String operationName,
        @Min(0) Integer standardSeconds,
        @Min(0) Integer setupSeconds,
        String primaryResourceMode,
        UUID primaryResourceId,
        @Size(max = 64) String resourceCode,
        List<UUID> secondaryResourceIds,
        Boolean optional,
        Map<String, Object> industryAttrs) {
}
