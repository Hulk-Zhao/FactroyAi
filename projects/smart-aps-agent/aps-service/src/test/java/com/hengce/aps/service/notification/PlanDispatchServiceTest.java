package com.hengce.aps.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C33 下发单测：仅 PUBLISHED、同 key 同 dispatchId 只入队一次、通道/地址校验。
 */
@ExtendWith(MockitoExtension.class)
class PlanDispatchServiceTest {

    private static final UUID TENANT = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-00000000d001");

    @Mock
    private PlanVersionRepository planVersionRepository;

    @Mock
    private NotificationOutboxRepository outboxRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private PlanDispatchService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        service = new PlanDispatchService(planVersionRepository, outboxRepository, auditLogRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("非 PUBLISHED 计划拒绝下发")
    void shouldRejectNonPublishedPlan() {
        when(planVersionRepository.find(TENANT, PLAN_ID))
                .thenReturn(Optional.of(plan("DRAFT")));

        assertThatThrownBy(() -> service.dispatch(PLAN_ID, "dispatch-key-001",
                List.of(target("EMAIL", "a@example.com")), "msg"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅 PUBLISHED");

        verify(outboxRepository, never()).insertTarget(any(), any(), any(), anyString(), anyString(),
                any(), any());
    }

    @Test
    @DisplayName("同一 Idempotency-Key 只入队一次，返回同一 dispatchId")
    void shouldReturnSameDispatchIdAndInsertOnce() {
        when(planVersionRepository.find(TENANT, PLAN_ID)).thenReturn(Optional.of(plan("PUBLISHED")));
        when(outboxRepository.countByDispatch(eq(TENANT), any())).thenReturn(0L, 2L);
        when(outboxRepository.insertTarget(eq(TENANT), any(), eq(PLAN_ID), anyString(), anyString(),
                any(), any())).thenReturn(1);
        when(outboxRepository.findQueuedAt(eq(TENANT), any())).thenReturn(Optional.of(now()));

        DispatchResultView first = service.dispatch(PLAN_ID, "dispatch-key-002",
                List.of(target("EMAIL", "a@example.com"), target("SMS", "13800000000")), "msg");
        DispatchResultView second = service.dispatch(PLAN_ID, "dispatch-key-002",
                List.of(target("EMAIL", "a@example.com"), target("SMS", "13800000000")), "msg");

        assertThat(first.dispatchId()).isEqualTo(second.dispatchId());
        assertThat(first.status()).isEqualTo("QUEUED");
        assertThat(first.targetCount()).isEqualTo(2);
        verify(outboxRepository, times(2)).insertTarget(eq(TENANT), any(), eq(PLAN_ID),
                anyString(), anyString(), any(), any());
        verify(auditLogRepository, times(1)).record(eq(TENANT), eq("PLAN_DISPATCHED"), eq("PLAN"),
                eq(PLAN_ID.toString()), isNull(), any(Map.class));
    }

    @Test
    @DisplayName("重复 (channel,address) 拒绝 42200")
    void shouldRejectDuplicateTargets() {
        when(planVersionRepository.find(TENANT, PLAN_ID)).thenReturn(Optional.of(plan("PUBLISHED")));

        assertThatThrownBy(() -> service.dispatch(PLAN_ID, "dispatch-key-003",
                List.of(target("EMAIL", "a@example.com"), target("email", "a@example.com")), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得重复");
    }

    @Test
    @DisplayName("缺失/非法 Idempotency-Key 拒绝 40000")
    void shouldRejectMissingOrInvalidKey() {
        assertThatThrownBy(() -> service.dispatch(PLAN_ID, null, List.of(target("EMAIL", "a@x.com")), null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.dispatch(PLAN_ID, "short", List.of(target("EMAIL", "a@x.com")), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("8-128");
    }

    private static PlanVersionRow plan(String status) {
        return new PlanVersionRow(PLAN_ID, TENANT, UUID.randomUUID(), "PLAN-20261104-001", 1, status,
                "{}", 0, null, null, null, null, null, 1, now());
    }

    private static PlanDispatchService.DispatchTargetCommand target(String channel, String address) {
        return new PlanDispatchService.DispatchTargetCommand(channel, address);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
