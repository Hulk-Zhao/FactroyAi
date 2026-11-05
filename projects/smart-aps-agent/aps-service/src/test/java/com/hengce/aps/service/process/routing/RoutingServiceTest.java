package com.hengce.aps.service.process.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingEdgeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingNodeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingReferenceMapper;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingEdge;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link RoutingService} 纯单测：覆盖复制版本、seqNo→UUID 映射、双 ACTIVE 冲突、删除引用校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutingServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID TEMPLATE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID RESOURCE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private RoutingMapper routingMapper;

    @Mock
    private RoutingNodeMapper routingNodeMapper;

    @Mock
    private RoutingEdgeMapper routingEdgeMapper;

    @Mock
    private RoutingReferenceMapper routingReferenceMapper;

    private RoutingService service;

    @BeforeEach
    void setUp() {
        service = new RoutingService(routingMapper, routingNodeMapper, routingEdgeMapper,
                routingReferenceMapper);
        lenient().when(routingReferenceMapper.selectExistingTemplateIds(anyCollection()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));
        lenient().when(routingReferenceMapper.selectExistingResourceIds(anyCollection()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));
    }

    @Test
    void saveGraphShouldRejectStaleVersion() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 5, RoutingService.STATUS_DRAFT));

        RoutingGraphCommand command = new RoutingGraphCommand(
                List.of(node(1), node(2)), List.of(), 4);

        BizException exception = assertThrows(BizException.class,
                () -> service.saveGraph(routingId, command));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertEquals(40901, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("工艺路线已被其他请求修改"), exception.getMessage());
        verify(routingMapper, never()).updateGraph(any());
    }

    @Test
    void saveGraphShouldReturnDomainMessageWhenOptimisticUpdateLosesRace() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 5, RoutingService.STATUS_DRAFT));
        when(routingNodeMapper.selectByRoutingId(routingId)).thenReturn(List.of());
        when(routingMapper.updateGraph(any())).thenReturn(0);

        RoutingGraphCommand command = new RoutingGraphCommand(List.of(node(1)), List.of(), 5);

        BizException exception = assertThrows(BizException.class,
                () -> service.saveGraph(routingId, command));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("工艺路线已被其他请求修改"), exception.getMessage());
        verify(routingNodeMapper, never()).insert(any());
    }

    @Test
    void saveGraphShouldMapSeqNoToNodeUuid() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 5, RoutingService.STATUS_DRAFT));
        when(routingMapper.updateGraph(any())).thenReturn(1);
        when(routingNodeMapper.selectByRoutingId(routingId)).thenReturn(List.of());
        when(routingEdgeMapper.selectByRoutingId(routingId)).thenReturn(List.of());

        RoutingGraphCommand command = new RoutingGraphCommand(
                List.of(node(1), node(2)), List.of(edge(1, 2, "FS", 7)), 5);
        service.saveGraph(routingId, command);

        ArgumentCaptor<RoutingNode> nodeCaptor = ArgumentCaptor.forClass(RoutingNode.class);
        verify(routingNodeMapper, times(2)).insert(nodeCaptor.capture());
        List<RoutingNode> insertedNodes = nodeCaptor.getAllValues();
        assertEquals("n1", insertedNodes.get(0).getNodeKey());
        assertEquals("n2", insertedNodes.get(1).getNodeKey());

        ArgumentCaptor<RoutingEdge> edgeCaptor = ArgumentCaptor.forClass(RoutingEdge.class);
        verify(routingEdgeMapper).insert(edgeCaptor.capture());
        RoutingEdge insertedEdge = edgeCaptor.getValue();
        assertEquals(insertedNodes.get(0).getId(), insertedEdge.getFromNodeId());
        assertEquals(insertedNodes.get(1).getId(), insertedEdge.getToNodeId());
        assertEquals("FS", insertedEdge.getDependType());
        assertEquals(7, insertedEdge.getLagMinutes());

        ArgumentCaptor<Routing> routingCaptor = ArgumentCaptor.forClass(Routing.class);
        verify(routingMapper).updateGraph(routingCaptor.capture());
        Map<String, Object> edgeSnapshot = routingCaptor.getValue().getEdges().get(0);
        assertEquals("n1", edgeSnapshot.get("from"));
        assertEquals("n2", edgeSnapshot.get("to"));
    }

    @Test
    void saveGraphShouldPersistThreeStatePrimaryResourceMode() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 5, RoutingService.STATUS_DRAFT));
        when(routingMapper.updateGraph(any())).thenReturn(1);
        when(routingNodeMapper.selectByRoutingId(routingId)).thenReturn(List.of());
        when(routingEdgeMapper.selectByRoutingId(routingId)).thenReturn(List.of());

        RoutingGraphCommand command = new RoutingGraphCommand(
                List.of(node(1, RoutingNodeModes.INHERIT, null),
                        node(2, RoutingNodeModes.FLOAT, null),
                        node(3, RoutingNodeModes.FIXED, RESOURCE_ID),
                        node(4, null, RESOURCE_ID)),
                List.of(edge(1, 2, "FS", 0), edge(2, 3, "FS", 0), edge(3, 4, "FS", 0)), 5);
        service.saveGraph(routingId, command);

        ArgumentCaptor<RoutingNode> nodeCaptor = ArgumentCaptor.forClass(RoutingNode.class);
        verify(routingNodeMapper, times(4)).insert(nodeCaptor.capture());
        List<RoutingNode> insertedNodes = nodeCaptor.getAllValues();

        Map<String, Object> inherit = insertedNodes.get(0).getOverrides();
        assertFalse(inherit.containsKey(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.INHERIT, RoutingOverrides.primaryResourceMode(inherit));

        Map<String, Object> floatOverrides = insertedNodes.get(1).getOverrides();
        assertTrue(floatOverrides.containsKey(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertNull(floatOverrides.get(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.FLOAT, RoutingOverrides.primaryResourceMode(floatOverrides));

        Map<String, Object> fixed = insertedNodes.get(2).getOverrides();
        assertEquals(RESOURCE_ID.toString(), fixed.get(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.FIXED, RoutingOverrides.primaryResourceMode(fixed));

        // 旧请求只带 primaryResourceId（无 mode）必须按 FIXED。
        Map<String, Object> legacy = insertedNodes.get(3).getOverrides();
        assertEquals(RoutingNodeModes.FIXED, RoutingOverrides.primaryResourceMode(legacy));
    }

    @Test
    void createShouldCopyFloatOverride() {
        UUID sourceId = UUID.randomUUID();
        UUID sourceNodeId = UUID.randomUUID();

        Routing source = routing(sourceId, PRODUCT_ID, 2, RoutingService.STATUS_ACTIVE);
        RoutingNode sourceNode = nodeEntity(sourceId, sourceNodeId, "n1", 1);
        Map<String, Object> floatOverrides = new LinkedHashMap<>();
        floatOverrides.put(RoutingOverrides.PRIMARY_RESOURCE_ID, null);
        sourceNode.setOverrides(floatOverrides);

        when(routingReferenceMapper.selectProductIdByCode("PRD-001")).thenReturn(PRODUCT_ID);
        when(routingMapper.countByProductVersion(PRODUCT_ID, "V2.0", null)).thenReturn(0L);
        when(routingMapper.selectById(sourceId)).thenReturn(source);
        when(routingNodeMapper.selectByRoutingId(sourceId)).thenReturn(List.of(sourceNode));
        when(routingEdgeMapper.selectByRoutingId(sourceId)).thenReturn(List.of());
        when(routingMapper.insert(any())).thenReturn(1);

        service.create(new RoutingCreateCommand(
                "PRD-001", "V2.0", LocalDate.of(2026, 1, 1), null, null, null, sourceId));

        ArgumentCaptor<RoutingNode> nodeCaptor = ArgumentCaptor.forClass(RoutingNode.class);
        verify(routingNodeMapper).insert(nodeCaptor.capture());
        Map<String, Object> copied = nodeCaptor.getValue().getOverrides();
        assertTrue(copied.containsKey(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertNull(copied.get(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.FLOAT, RoutingOverrides.primaryResourceMode(copied));
    }

    @Test
    void createShouldCopySourceVersion() {
        UUID sourceId = UUID.randomUUID();
        UUID sourceNodeOneId = UUID.randomUUID();
        UUID sourceNodeTwoId = UUID.randomUUID();

        Routing source = routing(sourceId, PRODUCT_ID, 2, RoutingService.STATUS_ACTIVE);
        source.setRoutingVersion("V1.0");

        RoutingNode sourceNodeOne = nodeEntity(sourceId, sourceNodeOneId, "n1", 1);
        sourceNodeOne.setOverrides(overrides(RESOURCE_ID));
        RoutingNode sourceNodeTwo = nodeEntity(sourceId, sourceNodeTwoId, "n2", 99);
        sourceNodeTwo.setSeqNo(2);

        RoutingEdge sourceEdge = new RoutingEdge();
        sourceEdge.setFromNodeId(sourceNodeOneId);
        sourceEdge.setToNodeId(sourceNodeTwoId);
        sourceEdge.setDependType("SS");
        sourceEdge.setLagMinutes(15);

        when(routingReferenceMapper.selectProductIdByCode("PRD-001")).thenReturn(PRODUCT_ID);
        when(routingMapper.countByProductVersion(PRODUCT_ID, "V2.0", null)).thenReturn(0L);
        when(routingMapper.selectById(sourceId)).thenReturn(source);
        when(routingNodeMapper.selectByRoutingId(sourceId))
                .thenReturn(List.of(sourceNodeOne, sourceNodeTwo));
        when(routingEdgeMapper.selectByRoutingId(sourceId)).thenReturn(List.of(sourceEdge));
        when(routingMapper.insert(any())).thenReturn(1);

        Routing created = service.create(new RoutingCreateCommand(
                "PRD-001", "V2.0", LocalDate.of(2026, 1, 1), null, null, null, sourceId));

        assertEquals(RoutingService.STATUS_DRAFT, created.getStatus());
        assertEquals("V2.0", created.getRoutingVersion());

        ArgumentCaptor<RoutingNode> nodeCaptor = ArgumentCaptor.forClass(RoutingNode.class);
        verify(routingNodeMapper, times(2)).insert(nodeCaptor.capture());
        List<RoutingNode> insertedNodes = nodeCaptor.getAllValues();
        assertEquals("n1", insertedNodes.get(0).getNodeKey());
        assertEquals(RESOURCE_ID.toString(),
                insertedNodes.get(0).getOverrides().get(RoutingOverrides.PRIMARY_RESOURCE_ID));

        ArgumentCaptor<RoutingEdge> edgeCaptor = ArgumentCaptor.forClass(RoutingEdge.class);
        verify(routingEdgeMapper).insert(edgeCaptor.capture());
        RoutingEdge copiedEdge = edgeCaptor.getValue();
        assertEquals(insertedNodes.get(0).getId(), copiedEdge.getFromNodeId());
        assertEquals(insertedNodes.get(1).getId(), copiedEdge.getToNodeId());
        assertEquals("SS", copiedEdge.getDependType());
        assertEquals(15, copiedEdge.getLagMinutes());
    }

    @Test
    void createShouldRejectDuplicateVersion() {
        when(routingReferenceMapper.selectProductIdByCode("PRD-001")).thenReturn(PRODUCT_ID);
        when(routingMapper.countByProductVersion(PRODUCT_ID, "V1.0", null)).thenReturn(1L);

        BizException exception = assertThrows(BizException.class, () -> service.create(
                new RoutingCreateCommand("PRD-001", "V1.0", LocalDate.of(2026, 1, 1),
                        null, List.of(node(1)), List.of(), null)));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void deleteShouldRejectWhenOrdersReference() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 1, RoutingService.STATUS_ACTIVE));
        when(routingReferenceMapper.countOrderReferences(routingId)).thenReturn(1L);
        when(routingReferenceMapper.countTaskReferences(routingId)).thenReturn(0L);

        BizException exception = assertThrows(BizException.class, () -> service.delete(routingId));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(routingMapper, never()).deleteById(any());
    }

    @Test
    void deleteShouldRejectWhenTasksReference() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 1, RoutingService.STATUS_ACTIVE));
        when(routingReferenceMapper.countOrderReferences(routingId)).thenReturn(0L);
        when(routingReferenceMapper.countTaskReferences(routingId)).thenReturn(3L);

        BizException exception = assertThrows(BizException.class, () -> service.delete(routingId));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("禁止删除"));
    }

    @Test
    void activateShouldConvergeOtherActiveRouting() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 3, RoutingService.STATUS_DRAFT));
        when(routingMapper.deactivateOthers(PRODUCT_ID, routingId)).thenReturn(1);
        when(routingMapper.updateStatus(routingId, RoutingService.STATUS_ACTIVE)).thenReturn(1);

        Routing activated = service.activate(routingId);

        assertEquals(RoutingService.STATUS_ACTIVE, activated.getStatus());
        assertEquals(4, activated.getVersion());
        verify(routingMapper).deactivateOthers(PRODUCT_ID, routingId);
        verify(routingMapper).updateStatus(routingId, RoutingService.STATUS_ACTIVE);
    }

    @Test
    void activateShouldReturnConflictWhenUniqueIndexRejectsDoubleActive() {
        UUID routingId = UUID.randomUUID();
        when(routingMapper.selectById(routingId)).thenReturn(
                routing(routingId, PRODUCT_ID, 3, RoutingService.STATUS_DRAFT));
        when(routingMapper.deactivateOthers(PRODUCT_ID, routingId)).thenReturn(0);
        when(routingMapper.updateStatus(routingId, RoutingService.STATUS_ACTIVE))
                .thenThrow(new DuplicateKeyException("uq_routing_one_active_per_product"));

        BizException exception = assertThrows(BizException.class, () -> service.activate(routingId));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    private static Routing routing(UUID id, UUID productId, int version, String status) {
        Routing routing = new Routing();
        routing.setId(id);
        routing.setProductId(productId);
        routing.setProductCode("PRD-001");
        routing.setRoutingVersion("V1.0");
        routing.setStatus(status);
        routing.setEffectiveDate(LocalDate.of(2026, 1, 1));
        routing.setVersion(version);
        routing.setIsPanel(false);
        routing.setDeleted(false);
        routing.setNodes(List.of());
        routing.setEdges(List.of());
        return routing;
    }

    private static RoutingNode nodeEntity(UUID routingId, UUID nodeId, String nodeKey, int seqNo) {
        RoutingNode node = new RoutingNode();
        node.setId(nodeId);
        node.setRoutingId(routingId);
        node.setNodeKey(nodeKey);
        node.setSeqNo(seqNo);
        node.setOperationTemplateId(TEMPLATE_ID);
        node.setOperationCode("OP" + seqNo);
        node.setOperationName("工序" + seqNo);
        node.setStandardSeconds(60);
        node.setSetupSeconds(10);
        node.setIsOptional(false);
        node.setOverrides(new LinkedHashMap<>());
        node.setVersion(1);
        node.setDeleted(false);
        return node;
    }

    private static Map<String, Object> overrides(UUID primaryResourceId) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(RoutingOverrides.PRIMARY_RESOURCE_ID, primaryResourceId.toString());
        return overrides;
    }

    private static RoutingNodeCommand node(int seqNo) {
        return new RoutingNodeCommand(seqNo, TEMPLATE_ID, "OP" + seqNo, "工序" + seqNo,
                60, 10, null, null, null, List.of(), false);
    }

    private static RoutingNodeCommand node(int seqNo, String mode, UUID primaryResourceId) {
        return new RoutingNodeCommand(seqNo, TEMPLATE_ID, "OP" + seqNo, "工序" + seqNo,
                60, 10, mode, primaryResourceId, null, List.of(), false);
    }

    private static RoutingEdgeCommand edge(int fromSeqNo, int toSeqNo, String dependType, Integer lagMinutes) {
        return new RoutingEdgeCommand(fromSeqNo, toSeqNo, dependType, lagMinutes, null, null);
    }
}
