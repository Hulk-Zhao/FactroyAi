package com.hengce.aps.service.process.routing;

/**
 * 路线节点稳定标识规则。
 *
 * <p>schema.md 5.5 示例使用 {@code n10}，且 {@code routing_node.node_key} 需与
 * {@code routing.nodes} 快照中的 {@code id} 对应。这里统一按 {@code "n" + seqNo}
 * 生成：同一路线内 seqNo 唯一，因此 node_key 唯一；同一 seqNo 在多次整体保存之间
 * 复用同一 node_key，从而复用同一 node UUID，保证 SE-04/甘特图拿到的标识稳定。</p>
 */
final class RoutingNodeKeys {

    private static final String PREFIX = "n";

    private RoutingNodeKeys() {
    }

    /**
     * 生成 node_key。
     *
     * @param seqNo 节点序号
     * @return node_key
     */
    public static String of(int seqNo) {
        return PREFIX + seqNo;
    }
}
