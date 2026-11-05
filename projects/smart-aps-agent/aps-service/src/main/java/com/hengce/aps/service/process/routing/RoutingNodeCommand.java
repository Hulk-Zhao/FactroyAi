package com.hengce.aps.service.process.routing;

import java.util.List;
import java.util.UUID;

/**
 * 工艺路线节点写入命令。
 *
 * <p>{@code primaryResourceId} 为 UUID 契约口径（C15 方案 B）；{@code resourceCode}
 * 仅用于识别「只传旧字段」的非法请求，服务层收到 code 而未给 UUID 时返回 42200。</p>
 *
 * <p>{@code primaryResourceMode} 为 C15 §20.3.1.5 冻结的三态表达，可空：缺省时按旧请求兼容规则
 * 推断（带 UUID→FIXED，未带→INHERIT），推断逻辑在 {@link RoutingNodeModes#normalize}。</p>
 *
 * @param seqNo 节点序号，路线内唯一
 * @param operationTemplateId 工序模版 UUID
 * @param operationCode 工序编码
 * @param operationName 工序名称
 * @param standardSeconds 标准加工秒数
 * @param setupSeconds 换型秒数
 * @param primaryResourceMode 主资源模式 INHERIT/FLOAT/FIXED，可空（旧请求）
 * @param primaryResourceId 主资源 UUID
 * @param resourceCode 旧资源编码字段（废弃，仅用于拒绝旧请求）
 * @param secondaryResourceIds 辅助资源 UUID 列表，null=缺键（回退模板）、空列表=显式无辅助
 * @param optional 是否可选工序
 */
public record RoutingNodeCommand(
        Integer seqNo,
        UUID operationTemplateId,
        String operationCode,
        String operationName,
        Integer standardSeconds,
        Integer setupSeconds,
        String primaryResourceMode,
        UUID primaryResourceId,
        String resourceCode,
        List<UUID> secondaryResourceIds,
        Boolean optional) {
}
