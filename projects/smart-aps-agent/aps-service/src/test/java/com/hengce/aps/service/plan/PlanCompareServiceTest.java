package com.hengce.aps.service.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanCompareRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanCompareRepository.SolveSnapshot;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import com.hengce.aps.service.plan.PlanCompareView.PlanCompareItem;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C35 择优对比单测：去重计数、42200 边界、确定性排序、metrics 原样透传。
 */
@ExtendWith(MockitoExtension.class)
class PlanCompareServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PlanVersionRepository planVersionRepository;

    @Mock
    private PlanCompareRepository planCompareRepository;

    private PlanCompareService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        service = new PlanCompareService(planVersionRepository, planCompareRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ordersByHardThenSoftThenPlanIdDeterministically() {
        UUID planA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID planB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID planC = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        stub(planA, snapshot(), -1L, 10L);
        stub(planB, snapshot(), -1L, 20L);
        stub(planC, snapshot(), 0L, 5L);

        PlanCompareView first = service.compare(List.of(planA, planB, planC));
        PlanCompareView second = service.compare(List.of(planC, planB, planA));

        assertEquals(List.of(planC, planB, planA),
                first.items().stream().map(PlanCompareItem::planId).toList());
        assertEquals(planC, first.recommendedPlanId());
        assertEquals(first.items(), second.items(), "同输入多次调用必须得到同一排序");
        assertEquals(first.recommendedPlanId(), second.recommendedPlanId());
        assertEquals(PlanCompareService.TIE_BREAK, first.tieBreak());
    }

    @Test
    void tiesBreakByPlanIdAscending() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        stub(low, snapshot(), 0L, 0L);
        stub(high, snapshot(), 0L, 0L);

        PlanCompareView view = service.compare(List.of(high, low));

        assertEquals(List.of(low, high), view.items().stream().map(PlanCompareItem::planId).toList());
        assertEquals(low, view.recommendedPlanId(), "并列时按 planId 升序");
    }

    @Test
    void dedupesBeforeCountingPlanIds() {
        UUID planA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID planB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID planC = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        stub(planA, snapshot(), -1L, 10L);
        stub(planB, snapshot(), 0L, 20L);
        stub(planC, snapshot(), 0L, 10L);

        PlanCompareView view = service.compare(List.of(planA, planB, planC, planB));

        assertEquals(3, view.items().size(), "4 个输入含 1 个重复，去重后 3 个仍合法");
    }

    @Test
    void rejectsMoreThanThreeUniquePlanIds() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();

        BizException exception = assertThrows(BizException.class,
                () -> service.compare(List.of(a, b, c, d)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("去重后必须为 2–3 个"), exception.getMessage());
    }

    @Test
    void rejectsFewerThanTwoUniquePlanIds() {
        UUID a = UUID.randomUUID();

        BizException exception = assertThrows(BizException.class, () -> service.compare(List.of(a, a)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
    }

    @Test
    void rejectsMissingPlanIdsAsBadRequest() {
        BizException exception = assertThrows(BizException.class, () -> service.compare(null));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
    }

    @Test
    void rejectsNonexistentPlan() {
        UUID missing = UUID.randomUUID();
        when(planVersionRepository.find(TENANT, missing)).thenReturn(Optional.empty());

        BizException exception = assertThrows(BizException.class,
                () -> service.compare(List.of(missing, UUID.randomUUID())));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("计划不存在"), exception.getMessage());
    }

    @Test
    void rejectsPlanWithoutSnapshot() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubRow(a, "{}");

        BizException exception = assertThrows(BizException.class, () -> service.compare(List.of(a, b)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("无快照"), exception.getMessage());
    }

    @Test
    void rejectsPlanWithoutSolvedRecord() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stubRow(a, snapshot());
        when(planCompareRepository.findLatestSolvedBefore(any(), any(), any())).thenReturn(Optional.empty());

        BizException exception = assertThrows(BizException.class, () -> service.compare(List.of(a, b)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("已求解记录"), exception.getMessage());
    }

    @Test
    void passesMetricsThroughAndDropsRemovedStepCounters() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stub(a, "{\"makespanSeconds\":120,\"fallbackMode\":\"HARD_ONLY\",\"stepCount\":5,"
                + "\"unimprovedStepCount\":2}", 0L, 100L);
        stub(b, snapshot(), 0L, 90L);

        PlanCompareView view = service.compare(List.of(a, b));

        PlanCompareItem item = view.items().stream()
                .filter(candidate -> candidate.planId().equals(a))
                .findFirst().orElseThrow();
        assertEquals(120, item.metrics().get("makespanSeconds"));
        assertEquals("HARD_ONLY", item.metrics().get("fallbackMode"));
        assertFalse(item.metrics().containsKey("stepCount"), "§24.2 已移除的 stepCount 不得透出");
        assertFalse(item.metrics().containsKey("unimprovedStepCount"),
                "§24.2 已移除的 unimprovedStepCount 不得透出");
    }

    private void stub(UUID planId, String metricsJson, Long hard, Long soft) {
        stubRow(planId, snapshot());
        when(planCompareRepository.findLatestSolvedBefore(TENANT, sandboxOf(planId), publishedAt()))
                .thenReturn(Optional.of(new SolveSnapshot(hard, soft, metricsJson)));
    }

    private void stubRow(UUID planId, String snapshot) {
        when(planVersionRepository.find(TENANT, planId))
                .thenReturn(Optional.of(row(planId, snapshot)));
    }

    private static PlanVersionRow row(UUID planId, String snapshot) {
        return new PlanVersionRow(planId, TENANT, sandboxOf(planId),
                "PLAN-" + planId.toString().substring(0, 8),
                12, "PUBLISHED", snapshot, 120, publishedAt(), null, null, null, null, 1, publishedAt());
    }

    private static UUID sandboxOf(UUID planId) {
        return UUID.nameUUIDFromBytes(planId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static OffsetDateTime publishedAt() {
        return OffsetDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    private static String snapshot() {
        return "{\"capturedAt\":\"2026-11-04T12:00:00Z\",\"tasks\":[]}";
    }
}
