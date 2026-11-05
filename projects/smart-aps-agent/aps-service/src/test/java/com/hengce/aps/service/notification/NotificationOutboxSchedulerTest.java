package com.hengce.aps.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository;
import com.hengce.aps.infrastructure.persistence.notification.NotificationOutboxRepository.OutboxRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * C33 outbox 轮询驱动单测：双门控、按 next_attempt_at 到期领取、退避 30s、第二次失败转 FAILED。
 */
class NotificationOutboxSchedulerTest {

    private static final UUID TENANT = TenantContext.DEFAULT_TENANT_ID;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationOutboxScheduler.class)
            .withBean(NotificationOutboxService.class, () -> new NotificationOutboxService(
                    new InMemoryOutboxRepository(), row -> {
                    }));

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("配置为 false 时（即使 dev profile）不装配轮询驱动")
    void shouldNotCreateSchedulerWhenDisabled() {
        runner.withPropertyValues("spring.profiles.active=dev",
                        "aps.notification.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationOutboxScheduler.class));
    }

    @Test
    @DisplayName("非 dev profile 下（即使配置为 true）不装配轮询驱动")
    void shouldNotCreateSchedulerOutsideDevProfile() {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "aps.notification.scheduler.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(NotificationOutboxScheduler.class));
    }

    @Test
    @DisplayName("dev + 配置 true 时装配轮询驱动")
    void shouldCreateSchedulerWhenDevAndEnabled() {
        runner.withPropertyValues("spring.profiles.active=dev",
                        "aps.notification.scheduler.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(NotificationOutboxScheduler.class));
    }

    @Test
    @DisplayName("只领取 next_attempt_at 已到期的 QUEUED 行；首次失败退避 30s")
    void shouldOnlyProcessDueRowsAndBackoffAfterFailure() {
        InMemoryOutboxRepository repository = new InMemoryOutboxRepository();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repository.add(row(1L, 0, now.minusSeconds(1)));
        repository.add(row(2L, 0, now.plusSeconds(30)));

        NotificationOutboxScheduler scheduler = schedulerFailing(repository);

        NotificationOutboxService.ProcessResult first = scheduler.poll();

        assertThat(first.processed()).isEqualTo(1);
        assertThat(first.retried()).isEqualTo(1);
        OutboxRow retried = repository.find(1L);
        assertThat(retried.attempts()).isEqualTo(1);
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(retried.nextAttemptAt()).isBetween(
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(NotificationOutboxService.BACKOFF_SECONDS - 2),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(NotificationOutboxService.BACKOFF_SECONDS + 2));
        assertThat(repository.find(2L).attempts()).isZero();
    }

    @Test
    @DisplayName("退避未到点不再处理；到点后第二次失败转 FAILED 且不再重试")
    void shouldMarkFailedOnSecondFailureAndStopRetrying() {
        InMemoryOutboxRepository repository = new InMemoryOutboxRepository();
        repository.add(row(1L, 0, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)));
        NotificationOutboxScheduler scheduler = schedulerFailing(repository);

        scheduler.poll();
        NotificationOutboxService.ProcessResult early = scheduler.poll();
        assertThat(early.processed()).isZero();
        assertThat(repository.find(1L).attempts()).isEqualTo(1);

        repository.makeDue(1L);
        NotificationOutboxService.ProcessResult second = scheduler.poll();

        assertThat(second.processed()).isEqualTo(1);
        assertThat(second.failed()).isEqualTo(1);
        OutboxRow failed = repository.find(1L);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(NotificationOutboxService.MAX_ATTEMPTS);

        assertThat(scheduler.poll().processed()).isZero();
        assertThat(repository.find(1L).attempts()).isEqualTo(NotificationOutboxService.MAX_ATTEMPTS);
    }

    private static NotificationOutboxScheduler schedulerFailing(NotificationOutboxRepository repository) {
        NotificationOutboxService service = new NotificationOutboxService(repository, row -> {
            throw new IllegalStateException("channel down");
        });
        return new NotificationOutboxScheduler(service);
    }

    private static OutboxRow row(long id, int attempts, OffsetDateTime nextAttemptAt) {
        return new OutboxRow(id, TENANT, UUID.randomUUID(), UUID.randomUUID(), "EMAIL",
                "qa@example.com", "plan ready", "QUEUED", attempts, nextAttemptAt, null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 内存版 outbox 仓储：模拟 V11 的 {@code status='QUEUED' AND next_attempt_at <= now} 领取语义。
     */
    private static final class InMemoryOutboxRepository extends NotificationOutboxRepository {

        private final List<OutboxRow> rows = new ArrayList<>();

        private InMemoryOutboxRepository() {
            super(null);
        }

        private void add(OutboxRow row) {
            rows.add(row);
        }

        private OutboxRow find(long id) {
            return rows.stream().filter(row -> row.id() == id).findFirst().orElseThrow();
        }

        private void makeDue(long id) {
            OutboxRow current = find(id);
            replace(id, with(current, current.attempts(),
                    OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), current.status()));
        }

        @Override
        public List<OutboxRow> listDue(UUID tenantId, OffsetDateTime now, int limit) {
            return rows.stream()
                    .filter(row -> "QUEUED".equals(row.status()))
                    .filter(row -> !row.nextAttemptAt().isAfter(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int markSent(UUID tenantId, long id, int attempts) {
            replace(id, with(find(id), attempts, find(id).nextAttemptAt(), "SENT"));
            return 1;
        }

        @Override
        public int markRetry(UUID tenantId, long id, int attempts, OffsetDateTime nextAttemptAt,
                             String lastError) {
            replace(id, with(find(id), attempts, nextAttemptAt, "QUEUED"));
            return 1;
        }

        @Override
        public int markFailed(UUID tenantId, long id, int attempts, String lastError) {
            replace(id, with(find(id), attempts, find(id).nextAttemptAt(), "FAILED"));
            return 1;
        }

        private void replace(long id, OutboxRow updated) {
            rows.replaceAll(row -> row.id() == id ? updated : row);
        }

        private static OutboxRow with(OutboxRow row, int attempts, OffsetDateTime nextAttemptAt,
                                      String status) {
            return new OutboxRow(row.id(), row.tenantId(), row.dispatchId(), row.planId(),
                    row.channel(), row.address(), row.message(), status, attempts, nextAttemptAt,
                    row.lastError(), row.createdAt());
        }
    }
}
