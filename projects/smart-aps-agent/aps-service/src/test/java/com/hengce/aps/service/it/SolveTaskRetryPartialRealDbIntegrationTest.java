package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.service.schedule.EngineRoute;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleResultWriter;
import com.hengce.aps.service.schedule.ScheduleService;
import com.hengce.aps.service.schedule.SolvePostProcessorChain;
import com.hengce.aps.service.schedule.SolveProgressPublisher;
import com.hengce.aps.service.schedule.SolveRetryClock;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.service.mrp.MrpStatus;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * C37 §25.8 真库集成测试：重试（注入时钟，不真睡）与 PARTIAL 语义必须真实落到 {@code solve_task}，
 * 并以 {@code GET 详情} 同一装配路径读回。
 *
 * <p>不装载 Spring 上下文：按 {@link RealDbEnvironment} 参数直连真库，Flyway 迁移到最新版本；
 * 仅使用内存替身替换引擎/装配，持久层与会话服务均为真实实现。本类创建的数据在用例后硬删除。</p>
 *
 * <p>真库门禁：类级条件不满足时整类跳过，{@code @BeforeEach}/{@code @AfterEach} 均不会执行，
 * 未启用真库的环境不会因为未初始化的连接而报错。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class SolveTaskRetryPartialRealDbIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private JdbcTemplate jdbc;

    private SolveTaskRepository solveTaskRepository;

    private ConstraintJustificationRepository justificationRepository;

    private final List<Long> createdTaskIds = new ArrayList<>();

    private final RecordingRetryClock retryClock = new RecordingRetryClock();

    private UUID sandboxId;

    @BeforeAll
    static void migrateToLatest() {
        if (!RealDbEnvironment.enabled()) {
            return;
        }
        Flyway.configure()
                .dataSource(RealDbEnvironment.jdbcUrl(), RealDbEnvironment.username(),
                        RealDbEnvironment.password())
                .locations("filesystem:../db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(RealDbEnvironment.jdbcUrl());
        dataSource.setUsername(RealDbEnvironment.username());
        dataSource.setPassword(RealDbEnvironment.password());
        jdbc = new JdbcTemplate(dataSource);
        solveTaskRepository = new SolveTaskRepository(jdbc);
        justificationRepository = new ConstraintJustificationRepository(jdbc);
        TenantContext.setTenantId(TENANT_ID);

        sandboxId = UUID.randomUUID();
        jdbc.update("INSERT INTO sandbox (id, tenant_id, name, type, snapshot) "
                        + "VALUES (?, ?, ?, 'WHAT_IF', '{}'::jsonb)",
                sandboxId, TENANT_ID, "C37-RETRY-" + sandboxId);
    }

    @AfterEach
    void tearDown() {
        if (jdbc == null) {
            createdTaskIds.clear();
            TenantContext.clear();
            return;
        }
        for (Long taskId : createdTaskIds) {
            jdbc.update("DELETE FROM constraint_justification WHERE solve_task_id = ?", taskId);
            jdbc.update("DELETE FROM solve_task WHERE id = ?", taskId);
        }
        createdTaskIds.clear();
        if (sandboxId != null) {
            jdbc.update("DELETE FROM sandbox WHERE id = ?", sandboxId);
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName("重试：首次 ENGINE_ERROR，退避 30s 后第 2 次 SOLVED；attemptCount=2，retryAt 由注入时钟产生")
    void retryShouldPersistAttemptMetricsAndSucceedOnSecondAttempt() throws Exception {
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.route(any())).thenReturn(engineRoute());
        when(scheduleService.toConfig(any(), any())).thenReturn(config(60));
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(failure("ENGINE_ERROR"), solved());

        SolveTaskLifecycleService lifecycle = lifecycle(scheduleService, 1, 30L);
        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        createdTaskIds.add(submitted.taskId());

        SolveTaskDetail solved = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.SOLVED.name(), solved.status());
        assertEquals(2, solved.metrics().get("attemptCount"));
        assertEquals(List.of("ENGINE_ERROR"), solved.metrics().get("retryReasons"));
        assertEquals(1, ((List<?>) solved.metrics().get("retryAt")).size());
        assertEquals(List.of(Duration.ofSeconds(30)), retryClock.waits());

        String metricsJson = jdbc.queryForObject(
                "SELECT metrics::text FROM solve_task WHERE id = ?", String.class, submitted.taskId());
        assertTrue(metricsJson.contains("\"attemptCount\": 2"), metricsJson);
        assertTrue(metricsJson.contains("retryReasons"), metricsJson);
        verify(scheduleService, timeout(1000).times(2)).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重试：TIMEOUT → SOLVER_UNAVAILABLE 第 2 次仍失败即终态 FAILED，退避仅 1 次、无第 3 次")
    void retryShouldStopAtSecondAttemptWhenStillFailing() throws Exception {
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.route(any())).thenReturn(engineRoute());
        when(scheduleService.toConfig(any(), any())).thenReturn(config(60));
        when(scheduleService.execute(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(failure("TIMEOUT"), failure("SOLVER_UNAVAILABLE"));

        SolveTaskLifecycleService lifecycle = lifecycle(scheduleService, 1, 30L);
        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        createdTaskIds.add(submitted.taskId());

        SolveTaskDetail failed = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.FAILED.name(), failed.status(), "第 2 次仍失败即终态 FAILED");
        assertEquals("SOLVER_UNAVAILABLE", failed.metrics().get("engineFallbackReason"));
        assertEquals(2, failed.metrics().get("attemptCount"), "首次 + 1 次重试，不得出现第 3 次尝试");
        assertEquals(List.of("TIMEOUT"), failed.metrics().get("retryReasons"),
                "只有第 1 次失败触发了重试");
        assertEquals(List.of(Duration.ofSeconds(30)), retryClock.waits(), "退避只发生 1 次");
        assertEquals(0, failed.metrics().get("persistedTaskCount"));
        verify(scheduleService, timeout(1000).times(2)).execute(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PARTIAL：部分排位只写 fallbackMode=PARTIAL，终态 FAILED、persistedTaskCount=0、tasks=[]")
    void partialShouldPersistFailedWithUnassignedMetricsOnly() throws Exception {
        UUID orderId = UUID.randomUUID();
        TaskAssignment partial = new TaskAssignment(null, orderId, "SO-1", 1, "工序1", null, null,
                "R-1", List.of(), null, null, null, null, BigDecimal.ONE, 1, "PLANNED", false, 1);
        SolverAdapter adapter = new SolverAdapter() {
            @Override
            public SolverEngine engine() {
                return SolverEngine.TIMEFOLD;
            }

            @Override
            public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
                return new ScheduleSolution("FAILED", null, List.of(partial), List.of(),
                        new SolveMetrics(10L, "TIME_LIMIT", List.of(), 0, Map.of()), "引擎异常，仅产出部分解");
            }
        };
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        when(assembler.assemble(any(), any())).thenReturn(assembledWithOperations(orderId, 2));
        ScheduleResultWriter resultWriter = mock(ScheduleResultWriter.class);
        StrategyService strategyService = mock(StrategyService.class);
        ScheduleService scheduleService = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false);

        SolveTaskLifecycleService lifecycle = lifecycle(scheduleService, 0, 0L);
        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        createdTaskIds.add(submitted.taskId());

        SolveTaskDetail detail = awaitTerminal(lifecycle, submitted.taskId(), 5000);

        assertEquals(SolveTaskStatus.FAILED.name(), detail.status(), "PARTIAL 不得新增状态值，终态仍是 FAILED");
        assertEquals("PARTIAL", detail.metrics().get("fallbackMode"));
        assertEquals(2, detail.metrics().get("expectedTaskCount"));
        assertEquals(1, detail.metrics().get("partialAssignedTaskCount"));
        assertEquals(1, detail.metrics().get("unassignedTaskCount"));
        assertEquals(Boolean.TRUE, detail.metrics().get("discardedBestSolution"));
        assertEquals(0, detail.metrics().get("persistedTaskCount"));
        assertTrue(detail.tasks().isEmpty(), "未排任务不得以任务形式出现在返回体");
        assertTrue(detail.errorMessage().contains("未排位 1"), detail.errorMessage());
        verify(resultWriter, never()).persist(any(), any(), anyLong());

        String metricsJson = jdbc.queryForObject(
                "SELECT metrics::text FROM solve_task WHERE id = ?", String.class, submitted.taskId());
        assertTrue(metricsJson.contains("\"fallbackMode\": \"PARTIAL\""), metricsJson);
        assertTrue(metricsJson.contains("\"unassignedTaskCount\": 1"), metricsJson);
        assertTrue(metricsJson.contains("\"persistedTaskCount\": 0"), metricsJson);
    }

    private static EngineRoute engineRoute() {
        return new EngineRoute("timefold", EngineRoute.SOURCE_DEFAULT, SolverEngine.TIMEFOLD,
                SolverEngine.TIMEFOLD, false, null, null, null);
    }

    private SolveTaskLifecycleService lifecycle(ScheduleService scheduleService, int maxRetries,
                                                long delaySeconds) {
        SolveProgressPublisher publisher = new SolveProgressPublisher(event -> { }, "none");
        MrpBlockingChecker mrpBlockingChecker = mock(MrpBlockingChecker.class);
        when(mrpBlockingChecker.evaluate(any(), anyList(), anyBoolean()))
                .thenReturn(new MrpBlockingDecision(false, MrpStatus.FULL, List.of(), "通过"));
        return new SolveTaskLifecycleService(solveTaskRepository, justificationRepository, scheduleService,
                publisher, mrpBlockingChecker, true, true, 8, 0, retryClock, maxRetries, delaySeconds,
                new SolvePostProcessorChain(List.of()), 0, 0);
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 1, "LATE_ACCEPTANCE", 0L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private SolveRequest request() {
        return new SolveRequest("T-1", sandboxId, null, "timefold", List.of("SO-1"), Map.of());
    }

    private static ScheduleService.ExecutionOutcome failure(String reason) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("phase", "FAILED");
        metrics.put("progress", 1.0);
        metrics.put("persistedTaskCount", 0);
        metrics.put("assignedTaskCount", 0);
        metrics.put("engineFallback", true);
        metrics.put("engineFallbackReason", reason);
        metrics.put("fallbackMode", "HARD_ONLY");
        return new ScheduleService.ExecutionOutcome(SolveTaskStatus.FAILED, null, null, metrics,
                "引擎失败", false, List.of());
    }

    private static ScheduleService.ExecutionOutcome solved() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("phase", "DONE");
        metrics.put("progress", 1.0);
        metrics.put("persistedTaskCount", 0);
        metrics.put("assignedTaskCount", 0);
        metrics.put("bestScore", "0hard/0soft");
        return new ScheduleService.ExecutionOutcome(SolveTaskStatus.SOLVED, 0L, 0L, metrics,
                "ok", false, List.of());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembledWithOperations(UUID orderId,
                                                                                     int operationCount) {
        List<SchedulingOperation> operations = new ArrayList<>();
        for (int index = 1; index <= operationCount; index++) {
            operations.add(new SchedulingOperation(index, "OP-" + index, "工序" + index, null,
                    BigDecimal.TEN, BigDecimal.ZERO, null, null, List.of(), false, List.of()));
        }
        SchedulingOrder order = new SchedulingOrder(orderId, "SO-1", "FG-1", BigDecimal.TEN,
                LocalDate.of(2030, 1, 1), "NORMAL", "UNFROZEN", null, operations);
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(), null, null, null,
                "FORWARD", List.of(order), List.of(), List.of(), List.of(), List.of());
        return new ScheduleProblemAssembler.AssembledProblem(TENANT_ID, null, problem, List.of(orderId));
    }

    private static SolveTaskDetail awaitTerminal(SolveTaskLifecycleService lifecycle, long taskId,
                                                 long timeoutMillis) throws InterruptedException {
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

    /**
     * 可注入重试时钟：记录退避时长但不阻塞调用线程，测试不真实休眠 30 秒。
     */
    private static final class RecordingRetryClock implements SolveRetryClock {

        private final List<Duration> waits = new java.util.concurrent.CopyOnWriteArrayList<>();

        private final AtomicLong ticks = new AtomicLong();

        @Override
        public synchronized Instant nowUtc() {
            return Instant.parse("2026-11-04T00:00:00Z").plusSeconds(ticks.incrementAndGet());
        }

        @Override
        public void await(Duration delay) {
            waits.add(delay);
        }

        @Override
        public java.util.concurrent.Future<?> schedule(Duration delay, Runnable task) {
            waits.add(delay);
            return java.util.concurrent.CompletableFuture.runAsync(task);
        }

        List<Duration> waits() {
            return List.copyOf(waits);
        }
    }
}
