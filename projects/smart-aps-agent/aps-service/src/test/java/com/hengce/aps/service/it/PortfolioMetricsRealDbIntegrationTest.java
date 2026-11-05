package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.service.mrp.MrpStatus;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleResultWriter;
import com.hengce.aps.service.schedule.ScheduleService;
import com.hengce.aps.service.schedule.SolvePostProcessorChain;
import com.hengce.aps.service.schedule.SolveProgressPublisher;
import com.hengce.aps.service.schedule.SolveRetryClock;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.service.schedule.portfolio.SolvePortfolioCoordinator;
import com.hengce.aps.service.schedule.portfolio.SolvePortfolioSettings;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.ScorePoint;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A1 真库验收：启用 portfolio 后，完整服务链路（真实 {@link ScheduleService#execute}、真实
 * {@link SolveTaskRepository}）只落库赢家，并把 {@code metrics.portfolio} 原样写入
 * {@code solve_task.metrics}。
 *
 * <p>引擎为确定性三候选替身（aps-service 测试作用域没有 solver-timefold，且按项目约束不改 pom）；
 * 真库部分（连接、Flyway 迁移、JSONB 写入与回读）全部真实。测试数据按租户/沙箱硬删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class PortfolioMetricsRealDbIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private JdbcTemplate jdbc;

    private SolveTaskRepository solveTaskRepository;

    private ConstraintJustificationRepository justificationRepository;

    private final List<Long> createdTaskIds = new ArrayList<>();

    private UUID sandboxId;

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
                sandboxId, TENANT_ID, "A1-PORTFOLIO-" + sandboxId);
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
    @DisplayName("三候选并行：metrics.portfolio 落库含 winnerProfile/candidates/scoreTimeline")
    void portfolioObservationShouldBePersistedWithWinner() throws Exception {
        UUID orderId = UUID.randomUUID();
        ProfileAdapter adapter = new ProfileAdapter(historyPoints());
        ScheduleProblemAssembler assembler = mock(ScheduleProblemAssembler.class);
        when(assembler.assemble(any(), any())).thenReturn(assembled(orderId, 2));
        ScheduleResultWriter resultWriter = mock(ScheduleResultWriter.class);
        when(resultWriter.persist(any(), any(), any(Long.class)))
                .thenReturn(new ScheduleResultWriter.WriteOutcome(2, 0));
        StrategyService strategyService = mock(StrategyService.class);
        SolvePortfolioSettings settings = SolvePortfolioSettings.forTest(true, 3, 5, 800, 1.0, true, 0);
        SolvePortfolioCoordinator coordinator = new SolvePortfolioCoordinator(
                settings, () -> 8, () -> 8L * 1024 * 1024 * 1024);
        ScheduleService scheduleService = new ScheduleService(List.of(adapter), assembler, resultWriter,
                solveTaskRepository, strategyService, true, false, coordinator);

        SolveTaskLifecycleService lifecycle = new SolveTaskLifecycleService(solveTaskRepository,
                justificationRepository, scheduleService, new SolveProgressPublisher(event -> { }, "none"),
                mrpBlocker(), true, true, 8, 0, new RecordingRetryClock(), 0, 0L,
                new SolvePostProcessorChain(List.of()), 2, 2);

        SolveTaskDetail submitted = lifecycle.submit(request(), null);
        createdTaskIds.add(submitted.taskId());
        SolveTaskDetail detail = awaitTerminal(lifecycle, submitted.taskId(), 10_000);

        assertEquals(SolveTaskStatus.SOLVED.name(), detail.status());
        assertEquals(0L, detail.scoreHard());
        assertEquals(-20L, detail.scoreSoft(), "赢家必须是软分最优的 ruin");

        String metricsJson = jdbc.queryForObject(
                "SELECT metrics::text FROM solve_task WHERE id = ?", String.class, submitted.taskId());
        assertNotNull(metricsJson);
        System.out.println("[A1-EVIDENCE] solve_task.metrics = " + metricsJson);
        Evidence.record("A1 portfolio 真库 metrics: " + metricsJson);
        assertTrue(metricsJson.contains("\"winnerProfile\": \"ruin\""), metricsJson);
        assertTrue(metricsJson.contains("\"candidateCount\": 3"), metricsJson);
        assertTrue(metricsJson.contains("\"profile\": \"baseline\""), metricsJson);
        assertTrue(metricsJson.contains("\"profile\": \"subchain\""), metricsJson);
        assertTrue(metricsJson.contains("\"profile\": \"ruin\""), metricsJson);
        assertTrue(metricsJson.contains("\"winner\": true"), metricsJson);
        assertTrue(metricsJson.contains("\"scoreTimeline\""), metricsJson);
        assertTrue(metricsJson.contains("\"timeToBestMs\""), metricsJson);

        Map<String, Object> portfolio = new LinkedHashMap<>(castMap(detail.metrics().get("portfolio")));
        assertEquals(3, ((Number) portfolio.get("candidateCount")).intValue());
        assertEquals("ruin", portfolio.get("winnerProfile"));
        assertEquals(false, portfolio.get("degraded"));
        assertEquals(1, countWinners(castList(portfolio.get("candidates"))), "只能有一个赢家");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object raw) {
        return raw instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static int countWinners(List<Object> candidates) {
        int count = 0;
        for (Object candidate : candidates) {
            if (candidate instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("winner"))) {
                count++;
            }
        }
        return count;
    }

    private static MrpBlockingChecker mrpBlocker() {
        MrpBlockingChecker blocker = mock(MrpBlockingChecker.class);
        when(blocker.evaluate(any(), anyList(), anyBoolean()))
                .thenReturn(new MrpBlockingDecision(false, MrpStatus.FULL, List.of(), "通过"));
        return blocker;
    }

    private SolveRequest request() {
        return new SolveRequest("T-1", sandboxId, null, "timefold", List.of("SO-1"), Map.of());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembled(UUID orderId, int operationCount) {
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

    private static List<ScorePoint> historyPoints() {
        List<ScorePoint> points = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            points.add(new ScorePoint(index * 100L, index, BigDecimal.ZERO,
                    BigDecimal.valueOf(-100 + index)));
        }
        return points;
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
     * 确定性三候选引擎替身：按 {@code portfolioProfile} 返回不同软分。
     */
    private static final class ProfileAdapter implements SolverAdapter {

        private final List<ScorePoint> history;

        ProfileAdapter(List<ScorePoint> history) {
            this.history = history;
        }

        @Override
        public SolverEngine engine() {
            return SolverEngine.TIMEFOLD;
        }

        @Override
        public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
            String profile = String.valueOf(config.parameters().getOrDefault("portfolioProfile", "baseline"));
            long soft = switch (profile) {
                case "subchain" -> -50L;
                case "ruin" -> -20L;
                default -> -100L;
            };
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("slotWaitMs", 0L);
            extra.put("profile", profile);
            SolveMetrics metrics = new SolveMetrics(50L, "TIME_LIMIT", history, 2, extra);
            return new ScheduleSolution("SOLVED",
                    new ScoreFact(BigDecimal.ZERO, BigDecimal.valueOf(soft), "0hard/" + soft + "soft"),
                    assignments(problem.orders().get(0).orderId()), List.of(), metrics, "ok");
        }

        private static List<TaskAssignment> assignments(UUID orderId) {
            OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
            List<TaskAssignment> assignments = new ArrayList<>(2);
            for (int index = 0; index < 2; index++) {
                OffsetDateTime begin = start.plusMinutes(index);
                assignments.add(new TaskAssignment(null, orderId, "SO-1", index, "工序" + index,
                        null, null, "R-1", List.of(), begin, begin, begin, begin.plusMinutes(1),
                        BigDecimal.ONE, index, "PLANNED", false, 1));
            }
            return assignments;
        }
    }

    /**
     * 可注入重试时钟：本用例 maxRetries=0，仅满足构造器。
     */
    private static final class RecordingRetryClock implements SolveRetryClock {

        private final AtomicLong ticks = new AtomicLong();

        @Override
        public Instant nowUtc() {
            return Instant.parse("2026-11-04T00:00:00Z").plusSeconds(ticks.incrementAndGet());
        }

        @Override
        public void await(Duration delay) {
        }

        @Override
        public Future<?> schedule(Duration delay, Runnable task) {
            return CompletableFuture.runAsync(task);
        }
    }
}
