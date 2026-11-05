package com.hengce.aps.service.process.template;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.OperationTemplateMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.RoutingNodeReferenceMapper;
import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工序模版服务单元测试：编码重复 409、分页 clamp、逻辑删除、引用删除拦截、乐观锁冲突。
 */
class OperationTemplateServiceTest {

    private OperationTemplateMapper operationTemplateMapper;

    private RoutingNodeReferenceMapper routingNodeReferenceMapper;

    private OperationTemplateService service;

    @BeforeEach
    void setUp() {
        operationTemplateMapper = mock(OperationTemplateMapper.class);
        routingNodeReferenceMapper = mock(RoutingNodeReferenceMapper.class);
        service = new OperationTemplateService(operationTemplateMapper, routingNodeReferenceMapper);
    }

    @Test
    void shouldRejectDuplicateCodeWithConflict() {
        OperationTemplateCommand command = new OperationTemplateCommand(
                "OP-001", "贴片", null, null, null, null, null, 0, 0, 0, 1, null);
        when(operationTemplateMapper.countByCodeVersion("OP-001", 1, null)).thenReturn(1L);

        BizException exception = assertThrows(BizException.class, () -> service.create(command));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        verify(operationTemplateMapper, never()).insert(any());
    }

    @Test
    void shouldClampPageSizeToUpperBound() {
        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(500);
        when(operationTemplateMapper.selectPageByFilter(any(), isNull(), isNull(), isNull(),
                eq("t.created_at"), eq("DESC")))
                .thenAnswer(invocation -> {
                    Page<OperationTemplate> page = invocation.getArgument(0);
                    page.setRecords(List.of(new OperationTemplate()));
                    page.setTotal(1L);
                    return page;
                });

        PageResult<OperationTemplate> result = service.list(query, null, null, null);

        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(operationTemplateMapper).selectPageByFilter(pageCaptor.capture(), isNull(), isNull(), isNull(),
                eq("t.created_at"), eq("DESC"));
        assertEquals(200, pageCaptor.getValue().getSize());
        assertEquals(200, result.size());
        assertEquals(1L, result.total());
    }

    @Test
    void shouldLogicallyDeleteWhenNotReferenced() {
        UUID id = UUID.randomUUID();
        OperationTemplate entity = new OperationTemplate();
        entity.setId(id);
        when(operationTemplateMapper.selectById(id)).thenReturn(entity);
        when(routingNodeReferenceMapper.countActiveReferences(id)).thenReturn(0L);
        when(operationTemplateMapper.deleteById(id)).thenReturn(1);

        service.delete(id);

        verify(operationTemplateMapper).deleteById(id);
    }

    @Test
    void shouldBlockDeleteWhenReferencedByRoutingNode() {
        UUID id = UUID.randomUUID();
        OperationTemplate entity = new OperationTemplate();
        entity.setId(id);
        when(operationTemplateMapper.selectById(id)).thenReturn(entity);
        when(routingNodeReferenceMapper.countActiveReferences(id)).thenReturn(2L);

        BizException exception = assertThrows(BizException.class, () -> service.delete(id));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        verify(operationTemplateMapper, never()).deleteById(any());
    }

    @Test
    void shouldRejectStaleVersionWithConflict() {
        UUID id = UUID.randomUUID();
        OperationTemplate existing = new OperationTemplate();
        existing.setId(id);
        existing.setTenantId(UUID.randomUUID());
        existing.setTemplateVersion(1);
        existing.setVersion(3);
        when(operationTemplateMapper.selectById(id)).thenReturn(existing);
        when(operationTemplateMapper.countByCodeVersion("OP-001", 1, id)).thenReturn(0L);
        when(operationTemplateMapper.update(any())).thenReturn(0);

        OperationTemplateCommand command = new OperationTemplateCommand(
                "OP-001", "贴片", null, null, null, null, null, 0, 0, 0, 1, 2);
        BizException exception = assertThrows(BizException.class, () -> service.update(id, command));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("工序模版已被其他请求修改"));
    }
}
