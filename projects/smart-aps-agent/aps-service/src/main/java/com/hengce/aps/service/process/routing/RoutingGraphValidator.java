package com.hengce.aps.service.process.routing;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingReferenceMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工艺路线图校验器：服务端不依赖前端，保存前完整校验 DAG。
 *
 * <p>覆盖的错误（均为 42200）：</p>
 * <ol>
 *   <li>seqNo 重复；</li>
 *   <li>operationTemplateId 不存在或已删除；</li>
 *   <li>primaryResourceId / secondaryResourceIds 指向不存在或已删除资源；</li>
 *   <li>边引用不存在的 seqNo；</li>
 *   <li>自环；</li>
 *   <li>依赖类型非 FS/SS/FF（SF 明确拒绝）；</li>
 *   <li>lagMinutes 为负；</li>
 *   <li>重复边（同 from + to + dependType）；</li>
 *   <li>多节点图存在孤立节点；</li>
 *   <li>存在环（DFS 报出环路径）。</li>
 * </ol>
 * <p>缺字段等形状问题返回 40000。</p>
 */
public class RoutingGraphValidator {

    /** 默认依赖类型。 */
    public static final String DEPEND_FS = "FS";

    /** 支持的依赖类型集合，SF 不在其中（V1 CHECK 约束）。 */
    public static final Set<String> SUPPORTED_DEPEND_TYPES = Set.of("FS", "SS", "FF");

    private static final int UNVISITED = 0;

    private static final int VISITING = 1;

    private static final int VISITED = 2;

    private final RoutingReferenceMapper referenceMapper;

    /**
     * 构造校验器。
     *
     * @param referenceMapper 只读引用查询
     */
    public RoutingGraphValidator(RoutingReferenceMapper referenceMapper) {
        this.referenceMapper = referenceMapper;
    }

    /**
     * 校验并返回规范化后的边（dependType 大写、lagMinutes 缺省 0）。
     *
     * @param nodes 节点命令列表，可空
     * @param edges 边命令列表，可空
     * @return 规范化后的边列表
     * @throws BizException 形状错误 40000；业务校验失败 42200
     */
    public List<RoutingEdgeCommand> validate(List<RoutingNodeCommand> nodes, List<RoutingEdgeCommand> edges) {
        List<RoutingNodeCommand> nodeList = nodes == null ? List.of() : nodes;
        List<RoutingEdgeCommand> edgeList = edges == null ? List.of() : edges;
        if (nodeList.isEmpty()) {
            if (!edgeList.isEmpty()) {
                throw unprocessable("节点列表为空时不允许存在边");
            }
            return List.of();
        }
        Map<Integer, RoutingNodeCommand> nodesBySeq = validateSeqNos(nodeList);
        validateReferences(nodeList);
        List<RoutingEdgeCommand> normalized = normalizeEdges(edgeList, nodesBySeq);
        validateNoIsolatedNodes(nodeList, normalized);
        detectCycle(normalized, nodesBySeq.keySet());
        return normalized;
    }

    private Map<Integer, RoutingNodeCommand> validateSeqNos(List<RoutingNodeCommand> nodes) {
        Map<Integer, RoutingNodeCommand> nodesBySeq = new LinkedHashMap<>();
        for (RoutingNodeCommand node : nodes) {
            if (node.seqNo() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "seqNo 不能为空");
            }
            if (node.seqNo() < 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "seqNo 不能为负数");
            }
            if (nodesBySeq.putIfAbsent(node.seqNo(), node) != null) {
                throw unprocessable("seqNo 重复: " + node.seqNo());
            }
            validateSeconds(node);
        }
        return nodesBySeq;
    }

    private void validateSeconds(RoutingNodeCommand node) {
        if (isNegative(node.standardSeconds()) || isNegative(node.setupSeconds())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "节点 seqNo=" + node.seqNo() + " 工时不能为负数");
        }
    }

    private void validateReferences(List<RoutingNodeCommand> nodes) {
        Set<UUID> templateIds = new LinkedHashSet<>();
        Set<UUID> resourceIds = new LinkedHashSet<>();
        for (RoutingNodeCommand node : nodes) {
            if (node.primaryResourceId() == null && isNotBlank(node.resourceCode())) {
                throw unprocessable("节点 seqNo=" + node.seqNo()
                        + " 仅提供 resourceCode，该字段已废弃；请改传 primaryResourceId（UUID）");
            }
            // 模式规范化同时校验：FIXED 缺 UUID → 40000；FLOAT 带 UUID → 42200；旧请求按兼容规则推断。
            String mode = RoutingNodeModes.normalize(node.primaryResourceMode(), node.primaryResourceId());
            addIfNotNull(templateIds, node.operationTemplateId());
            if (RoutingNodeModes.FIXED.equals(mode)) {
                // 只有 FIXED 才引用具体主资源；INHERIT/FLOAT 不产生资源引用校验。
                addIfNotNull(resourceIds, node.primaryResourceId());
            }
            if (node.secondaryResourceIds() != null) {
                for (UUID id : node.secondaryResourceIds()) {
                    addIfNotNull(resourceIds, id);
                }
            }
        }
        if (!templateIds.isEmpty()) {
            Set<UUID> existing = new HashSet<>(safeList(referenceMapper.selectExistingTemplateIds(templateIds)));
            templateIds.removeAll(existing);
            if (!templateIds.isEmpty()) {
                throw unprocessable("工序模版不存在或已删除: " + templateIds);
            }
        }
        if (!resourceIds.isEmpty()) {
            Set<UUID> existing = new HashSet<>(safeList(referenceMapper.selectExistingResourceIds(resourceIds)));
            resourceIds.removeAll(existing);
            if (!resourceIds.isEmpty()) {
                throw unprocessable("资源不存在或已删除: " + resourceIds);
            }
        }
    }

    private List<RoutingEdgeCommand> normalizeEdges(List<RoutingEdgeCommand> edges,
                                                    Map<Integer, RoutingNodeCommand> nodesBySeq) {
        List<RoutingEdgeCommand> normalized = new ArrayList<>(edges.size());
        Set<String> relations = new HashSet<>();
        for (RoutingEdgeCommand edge : edges) {
            int from = requireSeq(edge.fromSeqNo(), "fromSeqNo");
            int to = requireSeq(edge.toSeqNo(), "toSeqNo");
            if (!nodesBySeq.containsKey(from)) {
                throw unprocessable("边引用不存在的前序节点 seqNo: " + from);
            }
            if (!nodesBySeq.containsKey(to)) {
                throw unprocessable("边引用不存在的后继节点 seqNo: " + to);
            }
            if (from == to) {
                throw unprocessable("不允许自环: " + RoutingNodeKeys.of(from));
            }
            String dependType = normalizeDependType(edge.dependType());
            int lagMinutes = normalizeLag(edge.lagMinutes());
            String relation = from + "-" + to + "-" + dependType;
            if (!relations.add(relation)) {
                throw unprocessable("重复边: " + RoutingNodeKeys.of(from) + " -> "
                        + RoutingNodeKeys.of(to) + " (" + dependType + ")");
            }
            normalized.add(new RoutingEdgeCommand(from, to, dependType, lagMinutes, edge.from(), edge.to()));
        }
        return normalized;
    }

    private int requireSeq(Integer seqNo, String field) {
        if (seqNo == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        if (seqNo < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 不能为负数");
        }
        return seqNo;
    }

    private String normalizeDependType(String dependType) {
        if (dependType == null || dependType.isBlank()) {
            return DEPEND_FS;
        }
        String normalized = dependType.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_DEPEND_TYPES.contains(normalized)) {
            throw unprocessable("不支持的依赖类型: " + dependType + "（仅支持 FS/SS/FF）");
        }
        return normalized;
    }

    private int normalizeLag(Integer lagMinutes) {
        if (lagMinutes == null) {
            return 0;
        }
        if (lagMinutes < 0) {
            throw unprocessable("lagMinutes 不能为负数: " + lagMinutes);
        }
        return lagMinutes;
    }

    private void validateNoIsolatedNodes(List<RoutingNodeCommand> nodes, List<RoutingEdgeCommand> edges) {
        if (nodes.size() <= 1) {
            return;
        }
        Set<Integer> incident = new HashSet<>();
        for (RoutingEdgeCommand edge : edges) {
            incident.add(edge.fromSeqNo());
            incident.add(edge.toSeqNo());
        }
        List<String> isolated = new ArrayList<>();
        for (RoutingNodeCommand node : nodes) {
            if (node.seqNo() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "seqNo 不能为空");
            }
            if (!incident.contains(node.seqNo())) {
                isolated.add(RoutingNodeKeys.of(node.seqNo()));
            }
        }
        if (!isolated.isEmpty()) {
            throw unprocessable("存在孤立节点（无任何依赖边）: " + isolated);
        }
    }

    private void detectCycle(List<RoutingEdgeCommand> edges, Set<Integer> seqNos) {
        Map<Integer, List<Integer>> adjacency = new LinkedHashMap<>();
        for (Integer seqNo : seqNos) {
            adjacency.put(seqNo, new ArrayList<>());
        }
        for (RoutingEdgeCommand edge : edges) {
            adjacency.get(edge.fromSeqNo()).add(edge.toSeqNo());
        }
        Map<Integer, Integer> state = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (Integer seqNo : seqNos) {
            if (state.getOrDefault(seqNo, UNVISITED) == UNVISITED) {
                visit(seqNo, adjacency, state, stack);
            }
        }
    }

    private void visit(int current,
                       Map<Integer, List<Integer>> adjacency,
                       Map<Integer, Integer> state,
                       Deque<Integer> stack) {
        state.put(current, VISITING);
        stack.addLast(current);
        for (Integer next : adjacency.getOrDefault(current, List.of())) {
            int nextState = state.getOrDefault(next, UNVISITED);
            if (nextState == VISITING) {
                throw unprocessable("工艺路线存在环: " + cyclePath(stack, next));
            }
            if (nextState == UNVISITED) {
                visit(next, adjacency, state, stack);
            }
        }
        stack.removeLast();
        state.put(current, VISITED);
    }

    private String cyclePath(Deque<Integer> stack, int start) {
        StringBuilder builder = new StringBuilder();
        boolean started = false;
        for (Integer seqNo : stack) {
            if (seqNo == start) {
                started = true;
            }
            if (started) {
                if (!builder.isEmpty()) {
                    builder.append(" -> ");
                }
                builder.append(RoutingNodeKeys.of(seqNo));
            }
        }
        if (!builder.isEmpty()) {
            builder.append(" -> ");
        }
        builder.append(RoutingNodeKeys.of(start));
        return builder.toString();
    }

    private static void addIfNotNull(Set<UUID> target, UUID value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static List<UUID> safeList(List<UUID> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isNegative(Integer value) {
        return value != null && value < 0;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static BizException unprocessable(String message) {
        return new BizException(ErrorCode.UNPROCESSABLE_ENTITY, message);
    }
}
