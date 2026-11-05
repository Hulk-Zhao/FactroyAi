package com.hengce.aps.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.OutboxRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C33 outbox 状态流转单测：最多 2 次尝试、失败退避 30s、成功即终态。
 */
@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000c033");

    @Mock
    private NotificationOutboxRepository outboxRepository;

    private NotificationOutboxService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        service = new NotificationOutboxService(outboxRepository, row -> {
            throw new IllegalStateException("channel down");
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("首次失败：保持 QUEUED，attempts=1，next_attempt_at 退避 30s")
    void shouldKeepQueuedAndBackoffOnFirstFailure() {
        OutboxRow row = row(1L, 0);
        when(outboxRepository.listDue(eq(TENANT), any(), eq(10))).thenReturn(List.of(row));

        int processed = service.processDue(10);

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<OffsetDateTime> retryAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxRepository).markRetry(eq(TENANT), eq(1L), eq(1), retryAt.capture(),
                eq("channel down"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(retryAt.getValue()).isBetween(now.plusSeconds(NotificationOutboxService.BACKOFF_SECONDS - 2),
                now.plusSeconds(NotificationOutboxService.BACKOFF_SECONDS + 2));
        verify(outboxRepository, org.mockito.Mockito.never()).markFailed(any(), eq(1L), anyInt(), any());
    }

    @Test
    @DisplayName("第二次尝试成功：流转 SENT，attempts=2")
    void shouldMarkSentOnRetrySuccess() {
        OutboxRow row = row(2L, 1);
        when(outboxRepository.listDue(eq(TENANT), any(), eq(10))).thenReturn(List.of(row));
        service = new NotificationOutboxService(outboxRepository, configurable(row2 -> {
        }));

        service.processDue(10);

        verify(outboxRepository).markSent(eq(TENANT), eq(2L), eq(2));
    }

    @Test
    @DisplayName("两次尝试均失败：流转 FAILED 终态，不再退避")
    void shouldMarkFailedWhenAttemptsExhausted() {
        OutboxRow row = row(3L, 1);
        when(outboxRepository.listDue(eq(TENANT), any(), eq(10))).thenReturn(List.of(row));

        service.processDue(10);

        verify(outboxRepository).markFailed(eq(TENANT), eq(3L), eq(NotificationOutboxService.MAX_ATTEMPTS),
                eq("channel down"));
        verify(outboxRepository, org.mockito.Mockito.never()).markRetry(any(), eq(3L), anyInt(), any(), any());
    }

    @Test
    @DisplayName("首次即成功：流转 SENT，attempts=1")
    void shouldMarkSentOnFirstSuccess() {
        OutboxRow row = row(4L, 0);
        when(outboxRepository.listDue(eq(TENANT), any(), eq(10))).thenReturn(List.of(row));
        service = new NotificationOutboxService(outboxRepository, configurable(row2 -> {
        }));

        service.processDue(10);

        verify(outboxRepository).markSent(eq(TENANT), eq(4L), eq(1));
    }

    private static NotificationChannelSender configurable(java.util.function.Consumer<OutboxRow> action) {
        return action::accept;
    }

    private static OutboxRow row(long id, int attempts) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new OutboxRow(id, TENANT, UUID.randomUUID(), UUID.randomUUID(), "EMAIL",
                "qa@example.com", "plan ready", "QUEUED", attempts, now, null, now);
    }
}
