package com.hengce.aps.service.process.routing;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingEdgeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingNodeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingReferenceMapper;
import com.hengce.aps.infrastructure.persistence.process.routing.ResourceCodeRow;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingEdge;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工艺路线服务：列表、详情、新建版本、图读写、激活、删除。
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>路径 ID 为 routing UUID（C6）；列表筛选 {@code version} 是业务版本 routing_version，
 *       图写入 body 的 {@code version} 是乐观锁整数（C15 20.1）。</li>
 *   <li>节点资源统一用 UUID：写入 {@code routing_node.overrides}，不写不存在的
 *       {@code primary_resource_id} 列（C15 20.2/20.3）。</li>
 *   <li>边在库内保存 node UUID，对外按 seqNo 表达；{@code node_key = "n" + seqNo}，
 *       同一 seqNo 跨保存复用同一 node_key 与 node UUID，保证 SE-04/甘特稳定标识。</li>
 *   <li>同一产品仅一个 ACTIVE 路线：激活时事务内收敛其他 ACTIVE，并由唯一索引兜底，
 *       并发冲突返回 40901。</li>
 * </ul>
 */
@Service
public class RoutingService {

    /** 草稿状态。 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 生效状态。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 失效状态。 */
    public static final String STATUS_INACTIVE = "INACTIVE";

    private static final Set<String> SUPPORTED_STATUS =
            Set.of(STATUS_DRAFT, STATUS_ACTIVE, STATUS_INACTIVE);

    private static final int MAX_PRODUCT_CODE_LENGTH = 64;

    private static final int MAX_ROUTING_VERSION_LENGTH = 32;

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("productCode", "m.code"),
            Map.entry("routingVersion", "r.routing_version"),
            Map.entry("status", "r.status"),
            Map.entry("effectiveDate", "r.effective_date"),
            Map.entry("createdAt", "r.created_at"),
            Map.entry("updatedAt", "r.updated_at"),
            Map.entry("version", "r.version"));

    private final RoutingMapper routingMapper;

    private final RoutingNodeMapper routingNodeMapper;

    private final RoutingEdgeMapper routingEdgeMapper;

    private final RoutingReferenceMapper routingReferenceMapper;

    private final RoutingGraphValidator graphValidator;

    /**
     * 构造服务。
     *
     * @param routingMapper 路线 Mapper
     * @param routingNodeMapper 节点 Mapper
     * @param routingEdgeMapper 边 Mapper
     * @param routingReferenceMapper 只读引用查询
     */
    public RoutingService(RoutingMapper routingMapper,
                          RoutingNodeMapper routingNodeMapper,
                          RoutingEdgeMapper routingEdgeMapper,
                          RoutingReferenceMapper routingReferenceMapper) {
        this.routingMapper = routingMapper;
        this.routingNodeMapper = routingNodeMapper;
        this.routingEdgeMapper = routingEdgeMapper;
        this.routingReferenceMapper = routingReferenceMapper;
        this.graphValidator = new RoutingGraphValidator(routingReferenceMapper);
    }

    /**
     * 分页查询工艺路线，条件之间为 AND。
     *
     * @param query 分页与排序参数
     * @param productCode 产品编码，精确匹配 material.code
     * @param version 业务版本号 routing_version
     * @param status 状态 DRAFT/ACTIVE/INACTIVE
     * @return 分页结果
     * @throws BizException 排序或状态参数非法返回 40000
     */
    public PageResult<Routing> list(PageQuery query, String productCode, String version, String status) {
        PageQuery normalized = (query == null ? new PageQuery() : query).normalize();
        SortSpec sort = resolveSort(normalized.getSort());
        Page<Routing> page = new Page<>(normalized.getPage(), normalized.getSize());
        IPage<Routing> result = routingMapper.selectPageByFilter(page,
                blankToNull(productCode), blankToNull(version), normalizeStatus(status),
                sort.column(), sort.direction());
        return PageResult.of(result.getRecords(), normalized, result.getTotal());
    }

    /**
     * 查询工艺路线详情。
     *
     * @param id 路线主键
     * @return 路线
     * @throws BizException 不存在返回 40400
     */
    public Routing get(UUID id) {
        Routing routing = routingMapper.selectById(id);
        if (routing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工艺路线不存在: " + id);
        }
        return routing;
    }

    /**
     * 读取路线 DAG，边已由 node UUID 映射为 seqNo。
     *
     * @param id 路线主键
     * @return 图数据
     * @throws BizException 不存在返回 40400
     */
    public RoutingGraphData getGraph(UUID id) {
        return loadGraph(get(id));
    }

    /**
     * 新建路线版本：支持显式图、复制现有版本、空白草稿，默认 DRAFT。
     *
     * @param command 新建命令
     * @return 新建后的路线
     * @throws BizException 参数非法 40000、产品不存在/图非法 42200、版本重复 40901
     */
    @Transactional
    public Routing create(RoutingCreateCommand command) {
        validateCreateCommand(command);
        UUID productId = routingReferenceMapper.selectProductIdByCode(command.productCode());
        if (productId == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "产品不存在或已删除: " + command.productCode());
        }
        if (routingMapper.countByProductVersion(productId, command.routingVersion(), null) > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "工艺路线版本已存在: " + command.productCode() + "/" + command.routingVersion());
        }
        GraphInput graph = resolveCreateGraph(command);
        List<RoutingEdgeCommand> normalizedEdges = graphValidator.validate(graph.nodes(), graph.edges());

        UUID routingId = UUID.randomUUID();
        GraphRows rows = buildGraphRows(routingId, graph.nodes(), normalizedEdges, Map.of());
        Routing routing = new Routing();
        routing.setId(routingId);
        routing.setTenantId(TenantContext.getTenantId());
        routing.setProductId(productId);
        routing.setProductCode(command.productCode());
        routing.setRoutingVersion(command.routingVersion());
        routing.setEffectiveDate(command.effectiveDate());
        routing.setExpiryDate(command.expiryDate());
        routing.setStatus(STATUS_DRAFT);
        routing.setIsPanel(Boolean.FALSE);
        routing.setNodes(rows.nodeSnapshot());
        routing.setEdges(rows.edgeSnapshot());
        routing.setVersion(BaseEntity.DEFAULT_VERSION);
        routing.setDeleted(BaseEntity.DEFAULT_DELETED);
        OffsetDateTime now = now();
        routing.setCreatedAt(now);
        routing.setUpdatedAt(now);
        try {
            routingMapper.insert(routing);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "工艺路线版本已存在: " + command.productCode() + "/" + command.routingVersion());
        }
        insertNodes(rows);
        insertEdges(rows);
        return routing;
    }

    /**
     * 整体替换路线图。
     *
     * <p>先做乐观锁更新（version 不一致 → 40901），再落节点/边，任一步失败整事务回滚。</p>
     *
     * @param id 路线主键
     * @param command 新图与乐观锁版本
     * @return 保存后的图
     * @throws BizException 不存在 40400、版本冲突 40901、图非法 42200
     */
    @Transactional
    public RoutingGraphData saveGraph(UUID id, RoutingGraphCommand command) {
        Routing routing = get(id);
        if (command == null || command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!command.version().equals(routing.getVersion())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "工艺路线已被其他请求修改，请刷新后重试");
        }
        List<RoutingNodeCommand> nodeCommands = command.nodes() == null ? List.of() : command.nodes();
        if (nodeCommands.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "nodes 不能为空");
        }
        List<RoutingEdgeCommand> edgeCommands = command.edges() == null ? List.of() : command.edges();
        List<RoutingEdgeCommand> normalizedEdges = graphValidator.validate(nodeCommands, edgeCommands);

        Map<String, RoutingNode> existingByKey = new LinkedHashMap<>();
        for (RoutingNode node : routingNodeMapper.selectByRoutingId(id)) {
            existingByKey.put(node.getNodeKey(), node);
        }
        GraphRows rows = buildGraphRows(id, nodeCommands, normalizedEdges, existingByKey);
        routing.setNodes(rows.nodeSnapshot());
        routing.setEdges(rows.edgeSnapshot());
        routing.setUpdatedAt(now());
        int updated = routingMapper.updateGraph(routing);
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "工艺路线已被其他请求修改，请刷新后重试");
        }
        applyNodeRows(rows);
        routingEdgeMapper.deleteByRoutingId(id);
        insertEdges(rows);
        return loadGraph(get(id));
    }

    /**
     * 激活路线：同一产品仅一个 ACTIVE；已 ACTIVE 幂等返回。
     *
     * @param id 路线主键
     * @return 激活后的路线
     * @throws BizException 不存在 40400；并发激活冲突 40901
     */
    @Transactional
    public Routing activate(UUID id) {
        Routing routing = get(id);
        if (STATUS_ACTIVE.equals(routing.getStatus())) {
            return routing;
        }
        try {
            routingMapper.deactivateOthers(routing.getProductId(), id);
            int rows = routingMapper.updateStatus(id, STATUS_ACTIVE);
            if (rows == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "工艺路线不存在: " + id);
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同一产品已存在 ACTIVE 工艺路线，激活冲突: " + routing.getProductCode());
        }
        routing.setStatus(STATUS_ACTIVE);
        if (routing.getVersion() != null) {
            routing.setVersion(routing.getVersion() + 1);
        }
        return routing;
    }

    /**
     * 逻辑删除路线；被订单或排产任务引用时拒绝。
     *
     * @param id 路线主键
     * @throws BizException 不存在 40400；被引用 42200
     */
    @Transactional
    public void delete(UUID id) {
        get(id);
        long orderReferences = routingReferenceMapper.countOrderReferences(id);
        long taskReferences = routingReferenceMapper.countTaskReferences(id);
        if (orderReferences > 0 || taskReferences > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "工艺路线已被订单或排产任务引用，禁止删除: " + id);
        }
        int rows = routingMapper.deleteById(id);
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "工艺路线不存在: " + id);
        }
        routingNodeMapper.deleteByRoutingId(id);
        routingEdgeMapper.deleteByRoutingId(id);
    }

    private RoutingGraphData loadGraph(Routing routing) {
        UUID routingId = (UUID) routing.getId();
        List<RoutingNode> nodes = routingNodeMapper.selectByRoutingId(routingId);
        List<RoutingEdge> edges = routingEdgeMapper.selectByRoutingId(routingId);
        Map<UUID, Integer> seqByNodeId = new LinkedHashMap<>();
        Map<UUID, String> keyByNodeId = new LinkedHashMap<>();
        for (RoutingNode node : nodes) {
            seqByNodeId.put((UUID) node.getId(), node.getSeqNo());
            keyByNodeId.put((UUID) node.getId(), node.getNodeKey());
        }
        List<RoutingEdgeData> edgeData = new ArrayList<>(edges.size());
        for (RoutingEdge edge : edges) {
            Integer fromSeq = seqByNodeId.get(edge.getFromNodeId());
            Integer toSeq = seqByNodeId.get(edge.getToNodeId());
            if (fromSeq == null || toSeq == null) {
                continue;
            }
            edgeData.add(new RoutingEdgeData(fromSeq, toSeq,
                    keyByNodeId.get(edge.getFromNodeId()), keyByNodeId.get(edge.getToNodeId()),
                    edge.getDependType(), edge.getLagMinutes() == null ? 0 : edge.getLagMinutes()));
        }
        Set<UUID> resourceIds = new LinkedHashSet<>();
        for (RoutingNode node : nodes) {
            UUID primary = RoutingOverrides.primaryResourceId(node.getOverrides());
            if (primary != null) {
                resourceIds.add(primary);
            }
            List<UUID> secondary = RoutingOverrides.secondaryResourceIds(node.getOverrides());
            if (secondary != null) {
                for (UUID resourceId : secondary) {
                    if (resourceId != null) {
                        resourceIds.add(resourceId);
                    }
                }
            }
        }
        return new RoutingGraphData(routingId, routing.getVersion(), nodes, edgeData,
                loadResourceCodes(resourceIds));
    }

    private Map<UUID, String> loadResourceCodes(Set<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return Map.of();
        }
        List<ResourceCodeRow> rows = routingReferenceMapper.selectResourceCodes(resourceIds);
        Map<UUID, String> codes = new LinkedHashMap<>();
        if (rows == null) {
            return codes;
        }
        for (ResourceCodeRow row : rows) {
            if (row.getId() != null && row.getCode() != null) {
                codes.put(row.getId(), row.getCode());
            }
        }
        return codes;
    }

    private GraphInput resolveCreateGraph(RoutingCreateCommand command) {
        List<RoutingNodeCommand> nodes = command.nodes() == null ? List.of() : command.nodes();
        List<RoutingEdgeCommand> edges = command.edges() == null ? List.of() : command.edges();
        if (!nodes.isEmpty() || !edges.isEmpty()) {
            return new GraphInput(nodes, edges);
        }
        if (command.sourceRoutingId() == null) {
            return new GraphInput(List.of(), List.of());
        }
        return copyGraph(command.sourceRoutingId());
    }

    private GraphInput copyGraph(UUID sourceRoutingId) {
        Routing source = get(sourceRoutingId);
        UUID sourceId = (UUID) source.getId();
        List<RoutingNode> sourceNodes = routingNodeMapper.selectByRoutingId(sourceId);
        List<RoutingEdge> sourceEdges = routingEdgeMapper.selectByRoutingId(sourceId);
        Map<UUID, Integer> seqByNodeId = new LinkedHashMap<>();
        List<RoutingNodeCommand> nodes = new ArrayList<>(sourceNodes.size());
        for (RoutingNode node : sourceNodes) {
            seqByNodeId.put((UUID) node.getId(), node.getSeqNo());
            nodes.add(new RoutingNodeCommand(
                    node.getSeqNo(),
                    node.getOperationTemplateId(),
                    node.getOperationCode(),
                    node.getOperationName(),
                    node.getStandardSeconds(),
                    node.getSetupSeconds(),
                    RoutingOverrides.primaryResourceMode(node.getOverrides()),
                    RoutingOverrides.primaryResourceId(node.getOverrides()),
                    null,
                    RoutingOverrides.secondaryResourceIds(node.getOverrides()),
                    node.getIsOptional()));
        }
        List<RoutingEdgeCommand> edges = new ArrayList<>(sourceEdges.size());
        for (RoutingEdge edge : sourceEdges) {
            Integer fromSeq = seqByNodeId.get(edge.getFromNodeId());
            Integer toSeq = seqByNodeId.get(edge.getToNodeId());
            if (fromSeq == null || toSeq == null) {
                continue;
            }
            edges.add(new RoutingEdgeCommand(fromSeq, toSeq, edge.getDependType(),
                    edge.getLagMinutes(), null, null));
        }
        return new GraphInput(nodes, edges);
    }

    private GraphRows buildGraphRows(UUID routingId,
                                     List<RoutingNodeCommand> nodeCommands,
                                     List<RoutingEdgeCommand> edgeCommands,
                                     Map<String, RoutingNode> existingByKey) {
        Map<String, RoutingNode> entitiesByKey = new LinkedHashMap<>();
        List<RoutingNode> toInsert = new ArrayList<>();
        List<RoutingNode> toUpdate = new ArrayList<>();
        OffsetDateTime now = now();
        for (RoutingNodeCommand command : nodeCommands) {
            String nodeKey = RoutingNodeKeys.of(command.seqNo());
            RoutingNode existing = existingByKey.get(nodeKey);
            RoutingNode entity;
            if (existing == null) {
                entity = newEntity(now);
                toInsert.add(entity);
            } else {
                entity = existing;
                toUpdate.add(entity);
            }
            applyNodeFields(entity, routingId, nodeKey, command, now);
            entitiesByKey.put(nodeKey, entity);
        }
        List<RoutingNode> toDelete = new ArrayList<>();
        for (RoutingNode existing : existingByKey.values()) {
            if (!entitiesByKey.containsKey(existing.getNodeKey())) {
                toDelete.add(existing);
            }
        }
        List<Map<String, Object>> nodeSnapshot = new ArrayList<>(entitiesByKey.size());
        for (RoutingNode node : entitiesByKey.values()) {
            nodeSnapshot.add(nodeSnapshot(node));
        }
        List<RoutingEdge> edgeEntities = new ArrayList<>(edgeCommands.size());
        List<Map<String, Object>> edgeSnapshot = new ArrayList<>(edgeCommands.size());
        for (RoutingEdgeCommand command : edgeCommands) {
            RoutingNode from = entitiesByKey.get(RoutingNodeKeys.of(command.fromSeqNo()));
            RoutingNode to = entitiesByKey.get(RoutingNodeKeys.of(command.toSeqNo()));
            RoutingEdge edge = new RoutingEdge();
            edge.setId(UUID.randomUUID());
            edge.setTenantId(TenantContext.getTenantId());
            edge.setRoutingId(routingId);
            edge.setFromNodeId((UUID) from.getId());
            edge.setToNodeId((UUID) to.getId());
            edge.setDependType(command.dependType());
            edge.setLagMinutes(command.lagMinutes());
            edge.setVersion(BaseEntity.DEFAULT_VERSION);
            edge.setDeleted(BaseEntity.DEFAULT_DELETED);
            edge.setCreatedAt(now);
            edge.setUpdatedAt(now);
            edgeEntities.add(edge);
            edgeSnapshot.add(edgeSnapshot(from.getNodeKey(), to.getNodeKey(),
                    command.dependType(), command.lagMinutes()));
        }
        return new GraphRows(toInsert, toUpdate, toDelete, edgeEntities, nodeSnapshot, edgeSnapshot);
    }

    private RoutingNode newEntity(OffsetDateTime now) {
        RoutingNode entity = new RoutingNode();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TenantContext.getTenantId());
        entity.setVersion(BaseEntity.DEFAULT_VERSION);
        entity.setDeleted(BaseEntity.DEFAULT_DELETED);
        entity.setCreatedAt(now);
        return entity;
    }

    private void applyNodeFields(RoutingNode entity,
                                 UUID routingId,
                                 String nodeKey,
                                 RoutingNodeCommand command,
                                 OffsetDateTime now) {
        entity.setRoutingId(routingId);
        entity.setNodeKey(nodeKey);
        entity.setOperationTemplateId(command.operationTemplateId());
        entity.setOperationCode(command.operationCode());
        entity.setOperationName(command.operationName());
        entity.setStandardSeconds(command.standardSeconds());
        entity.setSetupSeconds(command.setupSeconds());
        entity.setIsOptional(Boolean.TRUE.equals(command.optional()));
        // 三态必须由 containsKey 语义落库：INHERIT 不写键、FLOAT 写 null、FIXED 写 UUID 字符串。
        String mode = RoutingNodeModes.normalize(command.primaryResourceMode(), command.primaryResourceId());
        entity.setOverrides(RoutingOverrides.build(mode, command.primaryResourceId(),
                command.secondaryResourceIds()));
        entity.setSeqNo(command.seqNo());
        entity.setUpdatedAt(now);
    }

    private Map<String, Object> nodeSnapshot(RoutingNode node) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", node.getNodeKey());
        if (node.getOperationTemplateId() != null) {
            snapshot.put("templateId", node.getOperationTemplateId().toString());
        }
        if (node.getOperationCode() != null) {
            snapshot.put("operationCode", node.getOperationCode());
        }
        if (node.getOperationName() != null) {
            snapshot.put("operationName", node.getOperationName());
        }
        if (node.getStandardSeconds() != null) {
            snapshot.put("standardSeconds", node.getStandardSeconds());
        }
        if (node.getSetupSeconds() != null) {
            snapshot.put("setupSeconds", node.getSetupSeconds());
        }
        snapshot.put("optional", Boolean.TRUE.equals(node.getIsOptional()));
        if (node.getOverrides() != null && !node.getOverrides().isEmpty()) {
            snapshot.put("overrides", node.getOverrides());
        }
        snapshot.put("seqNo", node.getSeqNo());
        return snapshot;
    }

    private Map<String, Object> edgeSnapshot(String fromNodeKey, String toNodeKey,
                                             String dependType, int lagMinutes) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("from", fromNodeKey);
        snapshot.put("to", toNodeKey);
        snapshot.put("type", dependType);
        snapshot.put("lagMinutes", lagMinutes);
        return snapshot;
    }

    private void insertNodes(GraphRows rows) {
        for (RoutingNode node : rows.nodesToInsert()) {
            routingNodeMapper.insert(node);
        }
    }

    private void applyNodeRows(GraphRows rows) {
        for (RoutingNode node : rows.nodesToInsert()) {
            routingNodeMapper.insert(node);
        }
        for (RoutingNode node : rows.nodesToUpdate()) {
            routingNodeMapper.update(node);
        }
        for (RoutingNode node : rows.nodesToDelete()) {
            routingNodeMapper.deleteById((UUID) node.getId());
        }
    }

    private void insertEdges(GraphRows rows) {
        for (RoutingEdge edge : rows.edges()) {
            routingEdgeMapper.insert(edge);
        }
    }

    private void validateCreateCommand(RoutingCreateCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (isBlank(command.productCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "productCode 不能为空");
        }
        if (command.productCode().length() > MAX_PRODUCT_CODE_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "productCode 长度不能超过 64");
        }
        if (isBlank(command.routingVersion())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "routingVersion 不能为空");
        }
        if (command.routingVersion().length() > MAX_ROUTING_VERSION_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "routingVersion 长度不能超过 32");
        }
        if (command.effectiveDate() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "effectiveDate 不能为空");
        }
        LocalDate expiryDate = command.expiryDate();
        if (expiryDate != null && expiryDate.isBefore(command.effectiveDate())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "expiryDate 不能早于 effectiveDate");
        }
    }

    private String normalizeStatus(String status) {
        if (isBlank(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_STATUS.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工艺路线状态不支持: " + status);
        }
        return normalized;
    }

    private SortSpec resolveSort(String sort) {
        if (isBlank(sort)) {
            return new SortSpec(SORT_COLUMNS.get("createdAt"), "DESC");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String column = SORT_COLUMNS.get(field);
        if (column == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 字段不支持: " + field);
        }
        String direction = "DESC";
        if (parts.length > 1 && !parts[1].isBlank()) {
            direction = parts[1].trim().toUpperCase(Locale.ROOT);
        }
        if (!"ASC".equals(direction) && !"DESC".equals(direction)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 方向仅支持 asc/desc");
        }
        return new SortSpec(column, direction);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 排序列与方向。
     *
     * @param column 数据库列名（含表别名）
     * @param direction ASC/DESC
     */
    private record SortSpec(String column, String direction) {
    }

    /**
     * 新建图输入（节点/边命令）。
     *
     * @param nodes 节点命令
     * @param edges 边命令
     */
    private record GraphInput(List<RoutingNodeCommand> nodes, List<RoutingEdgeCommand> edges) {
    }

    /**
     * 一次图构建产生的待持久化行与快照。
     *
     * @param nodesToInsert 待插入节点
     * @param nodesToUpdate 待更新节点
     * @param nodesToDelete 待逻辑删除节点
     * @param edges 待插入边
     * @param nodeSnapshot 节点 JSONB 快照
     * @param edgeSnapshot 边 JSONB 快照
     */
    private record GraphRows(List<RoutingNode> nodesToInsert,
                             List<RoutingNode> nodesToUpdate,
                             List<RoutingNode> nodesToDelete,
                             List<RoutingEdge> edges,
                             List<Map<String, Object>> nodeSnapshot,
                             List<Map<String, Object>> edgeSnapshot) {
    }
}
