package com.hengce.aps.service.process.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingReferenceMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link RoutingGraphValidator} 纯单测，覆盖八类图校验与 SF 拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutingGraphValidatorTest {

    private static final UUID TEMPLATE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID RESOURCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private RoutingReferenceMapper referenceMapper;

    private RoutingGraphValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RoutingGraphValidator(referenceMapper);
        lenient().when(referenceMapper.selectExistingTemplateIds(anyCollection()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));
        lenient().when(referenceMapper.selectExistingResourceIds(anyCollection()))
                .thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));
    }

    @Test
    void duplicateSeqNoShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(1, TEMPLATE_ID, null)), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("seqNo 重复"));
    }

    @Test
    void missingOperationTemplateShouldBeRejected() {
        UUID missing = UUID.fromString("33333333-3333-3333-3333-333333333333");
        lenient().when(referenceMapper.selectExistingTemplateIds(anyCollection()))
                .thenReturn(List.of());
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, missing, null)), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("工序模版不存在"));
    }

    @Test
    void deletedResourceShouldBeRejected() {
        lenient().when(referenceMapper.selectExistingResourceIds(anyCollection())).thenReturn(List.of());
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, RESOURCE_ID)), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("资源不存在"));
    }

    @Test
    void deprecatedResourceCodeOnlyShouldBeRejected() {
        RoutingNodeCommand command = new RoutingNodeCommand(1, TEMPLATE_ID, "OP1", "工序1",
                60, 10, null, null, "RES-001", List.of(), false);
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(command), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("primaryResourceId"));
    }

    @Test
    void edgeReferencingMissingSeqNoShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(2, TEMPLATE_ID, null)),
                List.of(edge(1, 9, "FS", 0))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("seqNo: 9"));
    }

    @Test
    void selfLoopShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null)), List.of(edge(1, 1, "FS", 0))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("自环"));
    }

    @Test
    void sfDependTypeShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(2, TEMPLATE_ID, null)),
                List.of(edge(1, 2, "SF", 0))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("不支持的依赖类型"));
    }

    @Test
    void negativeLagMinutesShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(2, TEMPLATE_ID, null)),
                List.of(edge(1, 2, "FS", -5))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("lagMinutes"));
    }

    @Test
    void duplicateEdgeShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(2, TEMPLATE_ID, null)),
                List.of(edge(1, 2, "FS", 0), edge(1, 2, "FS", 5))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("重复边"));
    }

    @Test
    void isolatedNodeShouldBeRejectedInMultiNodeGraph() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(2, TEMPLATE_ID, null),
                        node(3, TEMPLATE_ID, null)),
                List.of(edge(1, 2, "FS", 0))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("孤立节点"));
        assertTrue(exception.getMessage().contains("n3"));
    }

    @Test
    void cycleShouldBeRejectedWithPath() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null), node(2, TEMPLATE_ID, null),
                        node(3, TEMPLATE_ID, null)),
                List.of(edge(1, 2, "FS", 0), edge(2, 3, "SS", 0), edge(3, 1, "FF", 0))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("存在环"));
        assertTrue(exception.getMessage().contains("n1"));
    }

    @Test
    void validGraphShouldReturnNormalizedEdges() {
        List<RoutingEdgeCommand> normalized = validator.validate(
                List.of(node(1, TEMPLATE_ID, RESOURCE_ID), node(2, TEMPLATE_ID, null)),
                List.of(edge(1, 2, null, null)));
        assertEquals(1, normalized.size());
        assertEquals("FS", normalized.get(0).dependType());
        assertEquals(0, normalized.get(0).lagMinutes());
    }

    @Test
    void fixedModeWithoutPrimaryResourceShouldReturn42200() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null, "FIXED", null)), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void floatModeWithPrimaryResourceShouldBeRejected() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, null, "FLOAT", RESOURCE_ID)), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("FLOAT"));
    }

    @Test
    void floatModeWithoutPrimaryResourceShouldPassAndSkipResourceReference() {
        validator.validate(List.of(node(1, TEMPLATE_ID, null, "FLOAT", null)), List.of());
        // FLOAT 不引用具体资源，不应触发资源存在性查询。
        verify(referenceMapper, never()).selectExistingResourceIds(anyCollection());
    }

    @Test
    void legacyPrimaryResourceIdWithoutModeShouldBeTreatedAsFixed() {
        validator.validate(List.of(node(1, TEMPLATE_ID, RESOURCE_ID)), List.of());
        verify(referenceMapper).selectExistingResourceIds(anyCollection());
    }

    @Test
    void unsupportedModeShouldReturn42200() {
        BizException exception = assertThrows(BizException.class, () -> validator.validate(
                List.of(node(1, TEMPLATE_ID, RESOURCE_ID, "AUTO", RESOURCE_ID)), List.of()));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("primaryResourceMode"));
    }

    private static RoutingNodeCommand node(int seqNo, UUID templateId, UUID primaryResourceId) {
        return node(seqNo, templateId, primaryResourceId, null, primaryResourceId);
    }

    private static RoutingNodeCommand node(int seqNo, UUID templateId, UUID primaryResourceId,
                                           String mode, UUID modeResourceId) {
        return new RoutingNodeCommand(seqNo, templateId, "OP" + seqNo, "工序" + seqNo,
                60, 10, mode, modeResourceId, null, List.of(), false);
    }

    private static RoutingEdgeCommand edge(int fromSeqNo, int toSeqNo, String dependType, Integer lagMinutes) {
        return new RoutingEdgeCommand(fromSeqNo, toSeqNo, dependType, lagMinutes, null, null);
    }
}
