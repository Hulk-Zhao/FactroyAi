package com.hengce.aps.service.process.routing;

/**
 * 工艺路线依赖边写入命令，边按 seqNo 表达。
 *
 * @param fromSeqNo 前序节点 seqNo
 * @param toSeqNo 后继节点 seqNo
 * @param dependType 依赖类型 FS/SS/FF，空按 FS
 * @param lagMinutes 滞后分钟，空按 0
 * @param from 旧字段（node_key），仅响应兼容
 * @param to 旧字段（node_key），仅响应兼容
 */
public record RoutingEdgeCommand(
        Integer fromSeqNo,
        Integer toSeqNo,
        String dependType,
        Integer lagMinutes,
        String from,
        String to) {
}
