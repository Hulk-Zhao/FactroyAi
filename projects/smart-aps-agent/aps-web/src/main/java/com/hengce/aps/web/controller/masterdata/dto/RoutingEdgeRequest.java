package com.hengce.aps.web.controller.masterdata.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 工艺路线边请求体，按 seqNo 表达。
 *
 * <p>{@code lagMinutes} 不加 Bean Validation 最小值约束，负数由服务层统一返回 42200；
 * {@code dependType} 的 SF 拒绝同样在服务层处理。</p>
 *
 * @param fromSeqNo 前序节点 seqNo
 * @param toSeqNo 后继节点 seqNo
 * @param dependType 依赖类型 FS/SS/FF
 * @param lagMinutes 滞后分钟
 * @param from 旧字段（node_key），仅响应兼容
 * @param to 旧字段（node_key），仅响应兼容
 */
public record RoutingEdgeRequest(
        @NotNull @Min(0) Integer fromSeqNo,
        @NotNull @Min(0) Integer toSeqNo,
        String dependType,
        Integer lagMinutes,
        String from,
        String to) {
}
