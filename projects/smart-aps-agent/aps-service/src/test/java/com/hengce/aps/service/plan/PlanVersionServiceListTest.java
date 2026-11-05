package com.hengce.aps.service.plan;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划版本列表服务测试：分页偏移、摘要映射、越界页与默认参数。
 */
@ExtendWith(MockitoExtension.class)
class PlanVersionServiceListTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SANDBOX_ID = UUID.fromString("00000000-0000-0000-0000-000000000403");

    @Mock
    private PlanVersionRepository planVersionRepository;

    private PlanVersionService planVersionService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        planVersionService = new PlanVersionService(planVersionRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listShouldPageAndMapSummary() {
        PageQuery query = new PageQuery();
        query.setPage(2);
        query.setSize(10);
        when(planVersionRepository.countBySandbox(TENANT_ID, SANDBOX_ID)).thenReturn(12L);
        when(planVersionRepository.pageBySandbox(TENANT_ID, SANDBOX_ID, 10, 10))
                .thenReturn(List.of(row("PLAN-20261104-005", 4, "PUBLISHED", 2)));

        PageResult<PlanVersionSummaryView> result = planVersionService.list(query, SANDBOX_ID);

        assertEquals(2, result.page());
        assertEquals(10, result.size());
        assertEquals(12L, result.total());
        assertEquals(2, result.totalPages());
        assertEquals(1, result.items().size());
        PlanVersionSummaryView item = result.items().get(0);
        assertEquals("PLAN-20261104-005", item.planNo());
        assertEquals(4, item.version());
        assertEquals("PUBLISHED", item.status());
        assertEquals(2, item.taskCount());
        verify(planVersionRepository).pageBySandbox(TENANT_ID, SANDBOX_ID, 10, 10);
    }

    @Test
    void listBeyondLastPageShouldReturnEmptyItems() {
        PageQuery query = new PageQuery();
        query.setPage(9);
        query.setSize(10);
        when(planVersionRepository.countBySandbox(TENANT_ID, SANDBOX_ID)).thenReturn(12L);
        when(planVersionRepository.pageBySandbox(TENANT_ID, SANDBOX_ID, 80, 10)).thenReturn(List.of());

        PageResult<PlanVersionSummaryView> result = planVersionService.list(query, SANDBOX_ID);

        assertTrue(result.items().isEmpty());
        assertEquals(12L, result.total());
        assertEquals(2, result.totalPages());
    }

    @Test
    void listShouldUseDefaultPagingWhenQueryIsNull() {
        when(planVersionRepository.countBySandbox(TENANT_ID, SANDBOX_ID)).thenReturn(0L);
        when(planVersionRepository.pageBySandbox(TENANT_ID, SANDBOX_ID, 0, 20)).thenReturn(List.of());

        PageResult<PlanVersionSummaryView> result = planVersionService.list(null, SANDBOX_ID);

        assertEquals(1, result.page());
        assertEquals(20, result.size());
        assertEquals(0L, result.total());
        assertEquals(0, result.totalPages());
        assertTrue(result.items().isEmpty());
    }

    private static PlanVersionRow row(String planNo, int versionNo, String status, int taskCount) {
        return new PlanVersionRow(null, TENANT_ID, SANDBOX_ID, planNo, versionNo, status, null, taskCount,
                OffsetDateTime.parse("2026-11-03T21:40:00Z"), null, null, null, null, 1,
                OffsetDateTime.parse("2026-11-03T21:40:00Z"));
    }
}
