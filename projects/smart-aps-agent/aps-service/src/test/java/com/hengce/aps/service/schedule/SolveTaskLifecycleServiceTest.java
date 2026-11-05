package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository.SolveTaskRecord;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.service.mrp.MrpStatus;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 求解任务生命周期单测：PENDING/RUNNING、取消、超时与引擎异常降级、幂等、同步校验 422。
 *
 * <p>使用内存假仓库替代真库，调度线程与看门狗仍为真实实现，覆盖并发终态写入的幂等性。</p>
 */
class SolveTaskLifecycleServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    @BeforeEach
    void bindTenant() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void submitShouldEnterPendingOrRunningAndFinishSolved() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(outcome(SolveTaskStatus.SOLVED));
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        assertTrue(List.of("PENDING", "RUNNING").contains(submitted.status()), submitted.status());

        SolveTaskDetail finished = awaitTerminal(lifecycle, submitted.taskId(), 3000);
        assertEquals(SolveTaskStatus.SOLVED.name(), finished.status());
        assertEquals(submitted.taskId(), finished.resultTaskId());
        verify(scheduleService, timeout(1000)).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void idempotencyKeyShouldReturnExistingTaskWithoutSecondSolve() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        long existingId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD", "{}", "key-1", "PENDING");
        repository.finish(TENANT_ID, existingId, "SOLVED", 0L, -10L, "{\"phase\":\"DONE\"}", "ok");
        ScheduleService scheduleService = mockScheduleService(60);
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail returned = lifecycle.submit(request(), "key-1");

        assertEquals(existingId, returned.taskId());
        assertEquals(SolveTaskStatus.SOLVED.name(), returned.status());
        verify(scheduleService, never()).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void cancelShouldStopRunningTaskAndKeepCancelledTerminal() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        CountDownLatch solving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    solving.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return outcome(SolveTaskStatus.SOLVED);
                });
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        assertTrue(solving.await(3, TimeUnit.SECONDS), "求解应已开始");
        awaitStatus(lifecycle, submitted.taskId(), SolveTaskStatus.RUNNING, 2000);

        SolveTaskDetail cancelled = lifecycle.cancel(submitted.taskId());
        assertEquals(SolveTaskStatus.CANCELLED.name(), cancelled.status());

        release.countDown();
        Thread.sleep(200L);
        assertEquals(SolveTaskStatus.CANCELLED.name(), lifecycle.detail(submitted.taskId()).status());
        verify(scheduleService).terminateEarly(any(), any());
    }

    @Test
    void engineExceptionShouldFallbackWithMarker() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("timefold crashed"));
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail failed = awaitTerminal(lifecycle, submitted.taskId(), 3000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status());
        assertTrue(failed.degraded());
        assertEquals("ENGINE_ERROR", failed.metrics().get("engineFallbackReason"));
        assertEquals("HARD_ONLY", failed.metrics().get("fallbackMode"));
        assertNotNull(failed.metrics().get("fallbackAt"));
    }

    @Test
    void watchdogShouldTimeoutAndFallback() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(1);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return outcome(SolveTaskStatus.SOLVED);
                });
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail timedOut = awaitTerminal(lifecycle, submitted.taskId(), 6000);

        assertEquals(SolveTaskStatus.TIMEOUT.name(), timedOut.status());
        assertTrue(timedOut.degraded());
        assertEquals("TIMEOUT", timedOut.metrics().get("engineFallbackReason"));
        release.countDown();
    }

    @Test
    void validationFailureInSyncModeShouldThrow422WithoutFallback() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("validationFailure", true);
        metrics.put("phase", "FAILED");
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new ScheduleService.ExecutionOutcome(SolveTaskStatus.FAILED, null, null,
                        metrics, "租户下没有可用资源，无法排产", true, List.of()));
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, false, true);

        BizException exception = assertThrows(BizException.class, () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(SolveTaskStatus.FAILED.name(), repository.rows.values().iterator().next().status());
        assertTrue(repository.rows.values().iterator().next().metricsJson().contains("validationFailure"));
    }

    @Test
    void submitWithUnknownSandboxShouldReturnNotFoundInsteadOfForeignKey500() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        repository.markSandboxMissing();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, mockScheduleService(60), true, true);

        BizException exception = assertThrows(BizException.class, () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
        assertEquals(0L, repository.countActiveBySandbox(TENANT_ID, SANDBOX_ID));
    }

    @Test
    void submitShouldMapConcurrentSandboxDeletionToNotFoundInsteadOf500() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        repository.markSandboxDeletedConcurrently();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, mockScheduleService(60), true, true);

        BizException exception = assertThrows(BizException.class, () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void detailShouldTolerateNullValuedMetricsOnFailedTask() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        long taskId = repository.create(TENANT_ID, SANDBOX_ID, null, "TIMEFOLD", "{}", null, "PENDING");
        repository.finish(TENANT_ID, taskId, "FAILED", null, null,
                "{\"phase\":null,\"progress\":1.0,\"bestScore\":null,\"engineFallback\":true,"
                        + "\"engineFallbackReason\":null,\"fallbackMode\":\"HARD_ONLY\"}", "引擎异常");
        SolveTaskLifecycleService lifecycle = lifecycle(repository, mockScheduleService(60), true, true);

        SolveTaskDetail detail = lifecycle.detail(taskId);

        assertEquals("FAILED", detail.status());
        assertEquals("FAILED", detail.phase());
        assertEquals("FAILED", detail.errorCode());
        assertNull(detail.bestScore());
        assertTrue(detail.metrics().containsKey("bestScore"), "metrics 应保留原 JSON 的 null 键");
        assertNull(detail.metrics().get("bestScore"));
        assertTrue(detail.degraded());
    }

    @Test
    @DisplayName("全量求解与增量同口径：齐套未通过时受理阶段返回 42200，不创建 solve_task")
    void submitShouldBlockFullSolveWhenMrpShortage() {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        SolveProgressPublisher publisher = new SolveProgressPublisher(event -> { }, "none");
        ConstraintJustificationRepository justificationRepository = new ConstraintJustificationRepository(null);
        MrpBlockingChecker blocker = mock(MrpBlockingChecker.class);
        when(blocker.evaluate(any(), anyList(), anyBoolean()))
                .thenReturn(new MrpBlockingDecision(true, MrpStatus.SHORTAGE, List.of("M-001"),
                        "MRP 齐套未通过（SHORTAGE），缺料物料: M-001"));
        SolveTaskLifecycleService lifecycle = new SolveTaskLifecycleService(repository,
                justificationRepository, scheduleService, publisher, blocker,
                false, true, 8, 0, new RecordingRetryClock(), 0, 0L,
                new SolvePostProcessorChain(List.of()), 0, 0);

        BizException exception = assertThrows(BizException.class,
                () -> lifecycle.submit(request(), null));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("M-001"), "42200 响应必须带缺料明细");
        assertEquals(0, repository.countAll(), "齐套未通过不得创建 solve_task");
    }

    private static SolveTaskLifecycleService lifecycle(FakeSolveTaskRepository repository,
                                                      ScheduleService scheduleService,
                                                      boolean asyncEnabled,
                                                      boolean fallbackEnabled) {
        return lifecycle(repository, scheduleService, asyncEnabled, fallbackEnabled,
                new RecordingRetryClock(), 1, 30L);
    }

    private static SolveTaskLifecycleService lifecycle(FakeSolveTaskRepository repository,
                                                      ScheduleService scheduleService,
                                                      boolean asyncEnabled,
                                                      boolean fallbackEnabled,
                                                      SolveRetryClock retryClock,
                                                      int maxRetries,
                                                      long delaySeconds) {
        return lifecycle(repository, scheduleService, asyncEnabled, fallbackEnabled, retryClock,
                maxRetries, delaySeconds, new SolvePostProcessorChain(List.of()));
    }

    private static SolveTaskLifecycleService lifecycle(FakeSolveTaskRepository repository,
                                                      ScheduleService scheduleService,
                                                      boolean asyncEnabled,
                                                      boolean fallbackEnabled,
                                                      SolveRetryClock retryClock,
                                                      int maxRetries,
                                                      long delaySeconds,
                                                      SolvePostProcessorChain postProcessorChain) {
        return lifecycle(repository, scheduleService, asyncEnabled, fallbackEnabled, retryClock,
                maxRetries, delaySeconds, postProcessorChain, 0, 0);
    }

    private static SolveTaskLifecycleService lifecycle(FakeSolveTaskRepository repository,
                                                      ScheduleService scheduleService,
                                                      boolean asyncEnabled,
                                                      boolean fallbackEnabled,
                                                      SolveRetryClock retryClock,
                                                      int maxRetries,
                                                      long delaySeconds,
                                                      SolvePostProcessorChain postProcessorChain,
                                                      int maxConcurrentSolvers,
                                                      int workerConcurrency) {
        SolveProgressPublisher publisher = new SolveProgressPublisher(event -> { }, "none");
        ConstraintJustificationRepository justificationRepository = new ConstraintJustificationRepository(null);
        MrpBlockingChecker mrpBlockingChecker = mock(MrpBlockingChecker.class);
        when(mrpBlockingChecker.evaluate(any(), anyList(), anyBoolean()))
                .thenReturn(new MrpBlockingDecision(false, MrpStatus.FULL, List.of(), "通过"));
        return new SolveTaskLifecycleService(repository, justificationRepository, scheduleService, publisher,
                mrpBlockingChecker, asyncEnabled, fallbackEnabled, 8, 0, retryClock, maxRetries, delaySeconds,
                postProcessorChain, maxConcurrentSolvers, workerConcurrency);
    }

    private static ScheduleService mockScheduleService(int timeoutSeconds) {
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.route(any())).thenReturn(new EngineRoute("timefold",
                EngineRoute.SOURCE_DEFAULT, SolverEngine.TIMEFOLD, SolverEngine.TIMEFOLD,
                false, null, null, null));
        when(scheduleService.toConfig(any(), any())).thenReturn(config(timeoutSeconds));
        return scheduleService;
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 0L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private static SolveRequest request() {
        return new SolveRequest("T-1", SANDBOX_ID, null, "timefold", List.of("SO-1"), Map.of());
    }

    @Test
    void retryShouldStopWhenConfiguredAttemptsExhausted() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(fallbackOutcome("ENGINE_ERROR"));
        RecordingRetryClock clock = new RecordingRetryClock();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true, clock, 1, 30L);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail failed = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status());
        assertEquals("ENGINE_ERROR", failed.metrics().get("engineFallbackReason"));
        assertEquals(2, failed.metrics().get("attemptCount"), "首次 + 1 次重试，第 2 次失败即终态，不再有第 3 次");
        assertEquals(List.of("ENGINE_ERROR"), failed.metrics().get("retryReasons"));
        assertEquals(1, ((List<?>) failed.metrics().get("retryAt")).size());
        assertEquals(List.of(java.time.Duration.ofSeconds(30)), clock.waits(),
                "重试间隔固定 30s，由注入时钟记录，未真实休眠");
        verify(scheduleService, timeout(1000).times(2)).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void retryShouldSucceedOnSecondAttemptWithInjectedClock() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(fallbackOutcome("ENGINE_ERROR"), outcome(SolveTaskStatus.SOLVED));
        RecordingRetryClock clock = new RecordingRetryClock();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true, clock, 1, 30L);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail solved = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.SOLVED.name(), solved.status());
        assertEquals(2, solved.metrics().get("attemptCount"));
        assertEquals(List.of("ENGINE_ERROR"), solved.metrics().get("retryReasons"));
        assertEquals(1, clock.waits().size());
        assertEquals(List.of(java.time.Duration.ofSeconds(30)), clock.waits());
        assertEquals(1, ((List<?>) solved.metrics().get("retryAt")).size());
    }

    @Test
    void validationFailureShouldNotBeRetried() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("validationFailure", true);
        metrics.put("phase", "FAILED");
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new ScheduleService.ExecutionOutcome(SolveTaskStatus.FAILED, null, null,
                        metrics, "参数校验失败", true, List.of()));
        RecordingRetryClock clock = new RecordingRetryClock();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true, clock, 1, 30L);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail failed = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status());
        assertEquals(1, failed.metrics().get("attemptCount"));
        assertEquals(List.of(), failed.metrics().get("retryReasons"));
        assertTrue(clock.waits().isEmpty(), "校验失败不得重试");
        verify(scheduleService, timeout(1000).times(1)).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void nonRetryableBusinessFailureShouldNotBeRetried() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("phase", "FAILED");
        metrics.put("persistedTaskCount", 0);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new ScheduleService.ExecutionOutcome(SolveTaskStatus.FAILED, null, null,
                        metrics, "求解结果不完整", false, List.of()));
        RecordingRetryClock clock = new RecordingRetryClock();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true, clock, 1, 30L);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail failed = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status());
        assertEquals(1, failed.metrics().get("attemptCount"));
        assertTrue(clock.waits().isEmpty(), "覆盖不完整等业务结果不重试");
    }

    @Test
    void fiveSecondWatchdogTimeoutShouldRemainTimeoutWithoutPersist() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(5);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return outcome(SolveTaskStatus.SOLVED);
                });
        RecordingRetryClock clock = new RecordingRetryClock();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true, clock, 1, 30L);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail timedOut = awaitTerminal(lifecycle, submitted.taskId(), 8000);

        assertEquals(SolveTaskStatus.TIMEOUT.name(), timedOut.status());
        assertEquals(0, timedOut.metrics().get("persistedTaskCount"));
        assertEquals("TIMEOUT", timedOut.metrics().get("engineFallbackReason"));
        assertEquals(1, timedOut.metrics().get("attemptCount"), "超时落库也要带 attemptCount");
        assertTrue(clock.waits().isEmpty(), "看门狗超时是任务终态，不进入重试退避");
        release.countDown();
    }

    @Test
    void postProcessorShouldFireOnSuccessEvenIfAnotherProcessorThrows() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(outcome(SolveTaskStatus.SOLVED));
        RecordingPostProcessor recorder = new RecordingPostProcessor();
        SolvePostProcessorChain chain = new SolvePostProcessorChain(List.of(
                event -> {
                    throw new IllegalStateException("processor boom");
                }, recorder));

        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true,
                new RecordingRetryClock(), 0, 0L, chain);
        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        SolveTaskDetail finished = awaitTerminal(lifecycle, submitted.taskId(), 3000);

        assertEquals(SolveTaskStatus.SOLVED.name(), finished.status(),
                "后置处理器抛异常不得影响求解终态");
        assertEquals(1, recorder.events().size(), "SOLVED 必须触发一次后置钩子");
        SolveFinishEvent event = recorder.events().get(0);
        assertEquals("SOLVED", event.status());
        assertEquals(Long.valueOf(-10L), event.scoreSoft());
        assertTrue(event.metrics().containsKey("phase"));
    }

    @Test
    void postProcessorShouldFireOnFailure() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("timefold crashed"));
        RecordingPostProcessor recorder = new RecordingPostProcessor();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true,
                new RecordingRetryClock(), 0, 0L, new SolvePostProcessorChain(List.of(recorder)));

        SolveTaskDetail failed = awaitTerminal(lifecycle, lifecycle.submit(request(), null).taskId(), 3000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status());
        assertEquals(1, recorder.events().size());
        assertEquals("FAILED", recorder.events().get(0).status());
        assertEquals("ENGINE_ERROR", recorder.events().get(0).metrics().get("engineFallbackReason"));
    }

    @Test
    void postProcessorShouldFireOnTimeout() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(1);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return outcome(SolveTaskStatus.SOLVED);
                });
        RecordingPostProcessor recorder = new RecordingPostProcessor();
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true,
                new RecordingRetryClock(), 0, 0L, new SolvePostProcessorChain(List.of(recorder)));

        SolveTaskDetail timedOut = awaitTerminal(lifecycle, lifecycle.submit(request(), null).taskId(), 6000);
        release.countDown();

        assertEquals(SolveTaskStatus.TIMEOUT.name(), timedOut.status());
        assertEquals(1, recorder.events().size(), "TIMEOUT 必须触发后置钩子");
        assertEquals("TIMEOUT", recorder.events().get(0).status());
        assertEquals("TIMEOUT", recorder.events().get(0).metrics().get("engineFallbackReason"));
    }

    /**
     * A1 验收：工作线程与槽位对齐后，两个 SolveTask 必须真的同时处于 RUNNING；
     * 第三个任务排队等待写入 {@code queueWaitMs} 并累加进 {@code portfolio.slotWaitMs}。
     */
    @Test
    void twoSolveTasksShouldRunConcurrentlyAndExposeQueueWait() throws Exception {
        FakeSolveTaskRepository repository = new FakeSolveTaskRepository();
        ScheduleService scheduleService = mockScheduleService(60);
        CountDownLatch solving = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    solving.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return outcomeWithPortfolio(SolveTaskStatus.SOLVED);
                });
        SolveTaskLifecycleService lifecycle = lifecycle(repository, scheduleService, true, true,
                new RecordingRetryClock(), 0, 0L, new SolvePostProcessorChain(List.of()), 4, 2);

        SolveTaskDetail first = lifecycle.submit(request(), null);
        SolveTaskDetail second = lifecycle.submit(request(), null);

        assertTrue(solving.await(3, TimeUnit.SECONDS), "两个 SolveTask 必须同时进入求解（workerConcurrency=2）");
        assertEquals(SolveTaskStatus.RUNNING.name(), lifecycle.detail(first.taskId()).status());
        assertEquals(SolveTaskStatus.RUNNING.name(), lifecycle.detail(second.taskId()).status(),
                "第二个任务必须也是 RUNNING，而不是排队等待");

        SolveTaskDetail queued = lifecycle.submit(request(), null);
        awaitStatus(lifecycle, queued.taskId(), SolveTaskStatus.PENDING, 2000);
        Thread.sleep(150L);
        release.countDown();

        SolveTaskDetail firstDone = awaitTerminal(lifecycle, first.taskId(), 5000);
        SolveTaskDetail secondDone = awaitTerminal(lifecycle, second.taskId(), 5000);
        SolveTaskDetail queuedDone = awaitTerminal(lifecycle, queued.taskId(), 5000);
        assertEquals(SolveTaskStatus.SOLVED.name(), firstDone.status());
        assertEquals(SolveTaskStatus.SOLVED.name(), secondDone.status());
        assertEquals(SolveTaskStatus.SOLVED.name(), queuedDone.status());
        assertEquals(Long.valueOf(-10L), firstDone.scoreSoft(), "各自分数独立返回");
        assertEquals(Long.valueOf(-10L), secondDone.scoreSoft());

        long queueWaitMs = ((Number) queuedDone.metrics().get("queueWaitMs")).longValue();
        assertTrue(queueWaitMs >= 100L, "排队等待必须可观测，实际 queueWaitMs=" + queueWaitMs);
        Map<?, ?> portfolio = (Map<?, ?>) queuedDone.metrics().get("portfolio");
        assertNotNull(portfolio, "排队等待要累加进 portfolio.slotWaitMs");
        assertEquals(queueWaitMs, ((Number) portfolio.get("slotWaitMs")).longValue(),
                "slotWaitMs 必须包含工作队列等待");
        assertTrue(((Number) firstDone.metrics().get("queueWaitMs")).longValue() < 100L,
                "立即拿到 worker 的任务几乎没有排队等待");
    }

    private static ScheduleService.ExecutionOutcome outcomeWithPortfolio(SolveTaskStatus status) {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        Map<String, Object> portfolio = new java.util.LinkedHashMap<>();
        portfolio.put("enabled", true);
        portfolio.put("slotWaitMs", 0L);
        metrics.put("portfolio", portfolio);
        return new ScheduleService.ExecutionOutcome(status, 0L, -10L, metrics, "ok", false, List.of());
    }

    /**
     * 记录终态事件的后置处理器。
     */
    private static final class RecordingPostProcessor implements SolvePostProcessor {

        private final List<SolveFinishEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onSolveFinished(SolveFinishEvent event) {
            events.add(event);
        }

        List<SolveFinishEvent> events() {
            return List.copyOf(events);
        }
    }

    private static ScheduleService.ExecutionOutcome outcome(SolveTaskStatus status) {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        return new ScheduleService.ExecutionOutcome(status, 0L, -10L, metrics, "ok", false, List.of());
    }

    private static ScheduleService.ExecutionOutcome fallbackOutcome(String reason) {
        SolveTaskStatus status = "TIMEOUT".equals(reason) ? SolveTaskStatus.TIMEOUT : SolveTaskStatus.FAILED;
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        metrics.put("persistedTaskCount", 0);
        metrics.put("assignedTaskCount", 0);
        metrics.put("engineFallback", true);
        metrics.put("engineFallbackReason", reason);
        metrics.put("fallbackMode", "HARD_ONLY");
        return new ScheduleService.ExecutionOutcome(status, null, null, metrics, "引擎失败", false, List.of());
    }

    /**
     * 可注入重试时钟：记录退避时长但不阻塞调用线程；重试任务在公共池触发后再回到求解工作线程。
     */
    private static final class RecordingRetryClock implements SolveRetryClock {

        private final List<java.time.Duration> waits = new java.util.concurrent.CopyOnWriteArrayList<>();

        private final AtomicLong ticks = new AtomicLong();

        @Override
        public java.time.Instant nowUtc() {
            return java.time.Instant.parse("2026-11-04T00:00:00Z").plusSeconds(ticks.incrementAndGet());
        }

        @Override
        public void await(java.time.Duration delay) {
            waits.add(delay);
        }

        @Override
        public java.util.concurrent.Future<?> schedule(java.time.Duration delay, Runnable task) {
            waits.add(delay);
            return java.util.concurrent.CompletableFuture.runAsync(task);
        }

        List<java.time.Duration> waits() {
            return List.copyOf(waits);
        }
    }

    private static SolveTaskDetail awaitTerminal(SolveTaskLifecycleService lifecycle, long taskId, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            SolveTaskDetail detail = lifecycle.detail(taskId);
            if (SolveTaskStatus.fromNullable(detail.status()).isTerminal()) {
                return detail;
            }
            Thread.sleep(20L);
        }
        fail("任务未在 " + timeoutMillis + " ms 内进入终态");
        return null;
    }

    private static void awaitStatus(SolveTaskLifecycleService lifecycle, long taskId, SolveTaskStatus expected,
                                    long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.name().equals(lifecycle.detail(taskId).status())) {
                return;
            }
            Thread.sleep(20L);
        }
        fail("任务未在 " + timeoutMillis + " ms 内进入 " + expected);
    }

    /**
     * 内存假仓库，行为对齐 SolveTaskRepository 的条件终态语义。
     */
    private static final class FakeSolveTaskRepository extends SolveTaskRepository {

        private final ConcurrentMap<Long, SolveTaskRecord> rows = new ConcurrentHashMap<>();

        private final ConcurrentMap<String, Long> idempotencyKeys = new ConcurrentHashMap<>();

        private final AtomicLong sequence = new AtomicLong();

        private boolean sandboxMissing;

        private boolean sandboxFkViolation;

        int countAll() {
            return rows.size();
        }

        void markSandboxMissing() {
            this.sandboxMissing = true;
        }

        void markSandboxDeletedConcurrently() {
            this.sandboxFkViolation = true;
        }

        @Override
        public boolean sandboxExists(UUID tenantId, UUID sandboxId) {
            return !sandboxMissing;
        }

        private FakeSolveTaskRepository() {
            super(null);
        }

        @Override
        public long create(UUID tenantId, UUID sandboxId, UUID strategyId, String engine, String configJson,
                           String idempotencyKey, String status) {
            if (sandboxFkViolation) {
                throw new DataIntegrityViolationException("insert violates fk_solve_task_sandbox",
                        new SQLException("ERROR: insert or update on table \"solve_task\" violates foreign key "
                                + "constraint \"fk_solve_task_sandbox\""));
            }
            long id = sequence.incrementAndGet();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime startedAt = "RUNNING".equals(status) ? now : null;
            rows.put(id, new SolveTaskRecord(id, tenantId, sandboxId, strategyId, engine, status,
                    null, null, "{}", null, idempotencyKey, startedAt, null, now, now));
            if (idempotencyKey != null) {
                idempotencyKeys.put(tenantId + ":" + idempotencyKey, id);
            }
            return id;
        }

        @Override
        public Optional<SolveTaskRecord> findById(UUID tenantId, long solveTaskId) {
            SolveTaskRecord row = rows.get(solveTaskId);
            return row == null || !row.tenantId().equals(tenantId) ? Optional.empty() : Optional.of(row);
        }

        @Override
        public Optional<SolveTaskRecord> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
            Long id = idempotencyKeys.get(tenantId + ":" + idempotencyKey);
            return id == null ? Optional.empty() : findById(tenantId, id);
        }

        @Override
        public int markRunning(UUID tenantId, long solveTaskId) {
            SolveTaskRecord row = rows.get(solveTaskId);
            if (row == null || !"PENDING".equals(row.status())) {
                return 0;
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            rows.put(solveTaskId, new SolveTaskRecord(row.id(), row.tenantId(), row.sandboxId(), row.strategyId(),
                    row.engine(), "RUNNING", row.scoreHard(), row.scoreSoft(), row.metricsJson(),
                    row.errorMessage(), row.idempotencyKey(), now, null, row.createdAt(), now));
            return 1;
        }

        @Override
        public int mergeMetrics(UUID tenantId, long solveTaskId, String metricsJson) {
            return 1;
        }

        @Override
        public int finish(UUID tenantId, long solveTaskId, String status, Long scoreHard, Long scoreSoft,
                          String metricsJson, String errorMessage) {
            SolveTaskRecord row = rows.get(solveTaskId);
            if (row == null || !List.of("PENDING", "RUNNING").contains(row.status())) {
                return 0;
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            rows.put(solveTaskId, new SolveTaskRecord(row.id(), row.tenantId(), row.sandboxId(), row.strategyId(),
                    row.engine(), status, scoreHard, scoreSoft, metricsJson, errorMessage,
                    row.idempotencyKey(), row.startedAt(), now, row.createdAt(), now));
            return 1;
        }

        @Override
        public long countActiveBySandbox(UUID tenantId, UUID sandboxId) {
            return rows.values().stream()
                    .filter(row -> row.sandboxId().equals(sandboxId))
                    .filter(row -> List.of("PENDING", "RUNNING").contains(row.status()))
                    .count();
        }
    }
}
