package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
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
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A1：ScheduleService 接入 Portfolio 后，metrics 合并、赢家落库与全失败映射。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleServicePortfolioTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    private static final long GIB = 1024L * 1024 * 1024;

    @Mock
    private ScheduleProblemAssembler assembler;

    @Mock
    private ScheduleResultWriter resultWriter;

    @Mock
    private SolveTaskRepository solveTaskRepository;

    @Mock
    private StrategyService strategyService;

    @Test
    void 三候选选优后只落库赢家且metrics带portfolio与截断轨迹() {
        UUID orderId = UUID.randomUUID();
        ProfileAdapter adapter = new ProfileAdapter(historyPoints(250));
        adapter.softScores.put("baseline", -100L);
        adapter.softScores.put("subchain", -50L);
        adapter.softScores.put("ruin", -75L);
        ScheduleSolution expectedWinner = profileSolution(orderId, adapter, "subchain", -50L);
        ScheduleService service = service(adapter);
        when(assembler.assemble(any(), any())).thenReturn(assembled(orderId, 3));
        when(resultWriter.persist(any(), eq(expectedWinner), eq(7L)))
                .thenReturn(new ScheduleResultWriter.WriteOutcome(3, 0));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(5),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.SOLVED, outcome.status());
        assertEquals(0L, outcome.scoreHard());
        assertEquals(-50L, outcome.scoreSoft(), "赢家应是软分最好的 subchain");
        Map<String, Object> portfolio = portfolio(outcome.metrics());
        assertEquals(true, portfolio.get("enabled"));
        assertEquals("subchain", portfolio.get("winnerProfile"));
        assertEquals(3, portfolio.get("candidateCount"));
        assertEquals(false, portfolio.get("degraded"));
        assertEquals(3L, portfolio.get("slotWaitMs"), "候选抢槽等待应进 slotWaitMs");
        List<?> candidates = (List<?>) portfolio.get("candidates");
        assertEquals(3, candidates.size());
        assertTrue(countWinners(candidates) == 1, "只能有一个赢家");
        Map<?, ?> firstCandidate = (Map<?, ?>) candidates.get(0);
        assertTrue(firstCandidate.containsKey("hardScore"), "C54 候选字段扁平：hardScore 在顶层");
        assertTrue(firstCandidate.containsKey("softScore"), "C54 候选字段扁平：softScore 在顶层");
        assertFalse(firstCandidate.containsKey("score"), "不得自造嵌套 score 对象");
        assertFalse(firstCandidate.containsKey("assignedTaskCount"), "不得自造契约外字段");
        List<?> history = (List<?>) outcome.metrics().get("scoreHistory");
        assertEquals(200, history.size(), "scoreHistory 必须截断到 200 点");
        assertEquals(2490L, outcome.metrics().get("timeToBestMs"), "timeToBestMs 是首个最佳解的耗时");
        assertEquals(0L, outcome.metrics().get("queueWaitMs"), "同步路径无排队等待");
        assertNotNull(outcome.problem(), "A1 钩子载荷：problem 必须可读");
        assertNotNull(outcome.solution(), "A1 钩子载荷：solution 必须可读");
        verify(resultWriter).persist(any(), eq(expectedWinner), eq(7L));
    }

    @Test
    void 全部候选失败时任务FAILED但保留portfolio证据() {
        UUID orderId = UUID.randomUUID();
        ProfileAdapter adapter = new ProfileAdapter(historyPoints(3));
        adapter.failAll = true;
        ScheduleService service = service(adapter);
        when(assembler.assemble(any(), any())).thenReturn(assembled(orderId, 3));

        ScheduleService.ExecutionOutcome outcome = service.execute(request(), 7L, config(5),
                adapter, () -> false, (phase, progress, bestScore) -> { });

        assertEquals(SolveTaskStatus.FAILED, outcome.status());
        assertEquals("ENGINE_ERROR", outcome.metrics().get("engineFallbackReason"));
        assertEquals(3, outcome.metrics().get("expectedTaskCount"));
        assertEquals(0, outcome.metrics().get("assignedTaskCount"));
        Map<String, Object> portfolio = portfolio(outcome.metrics());
        assertNotNull(portfolio, "全失败也必须保留 metrics.portfolio");
        assertNull(portfolio.get("winnerProfile"));
        assertEquals(0L, outcome.metrics().get("queueWaitMs"));
    }

    private ScheduleService service(SolverAdapter adapter) {
        SolvePortfolioSettings settings = SolvePortfolioSettings.forTest(true, 3, 5, 800, 1.0, true, 0);
        SolvePortfolioCoordinator coordinator = new SolvePortfolioCoordinator(
                settings, () -> 8, () -> 8 * GIB);
        return new ScheduleService(List.of(adapter), assembler, resultWriter, solveTaskRepository,
                strategyService, true, false, coordinator);
    }

    private static Map<String, Object> portfolio(Map<String, Object> metrics) {
        Object raw = metrics.get("portfolio");
        assertNotNull(raw, "metrics.portfolio 必须存在");
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static int countWinners(List<?> candidates) {
        int count = 0;
        for (Object candidate : candidates) {
            if (Boolean.TRUE.equals(((Map<?, ?>) candidate).get("winner"))) {
                count++;
            }
        }
        return count;
    }

    private static List<ScorePoint> historyPoints(int count) {
        List<ScorePoint> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(new ScorePoint(index == 0 ? 5L : index * 10L, index,
                    BigDecimal.ZERO, BigDecimal.valueOf(-count + index)));
        }
        return points;
    }

    private static ScheduleSolution profileSolution(
            UUID orderId, ProfileAdapter adapter, String profile, long soft) {
        return adapter.buildSolution(orderId, 3, profile, soft, adapter.history);
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 0, "LATE_ACCEPTANCE", 7L, 0L, 10,
                "FORWARD", true, Map.of());
    }

    private static SolveRequest request() {
        return new SolveRequest("T-1", SANDBOX_ID, null, "timefold", List.of("SO-1"), Map.of());
    }

    private static ScheduleProblemAssembler.AssembledProblem assembled(UUID orderId, int operationCount) {
        List<SchedulingOperation> operations = new ArrayList<>();
        for (int index = 1; index <= operationCount; index++) {
            operations.add(new SchedulingOperation(index, "OP-" + index, "工序" + index, null,
                    BigDecimal.TEN, BigDecimal.ZERO, null, null, List.of(), false, List.of()));
        }
        SchedulingOrder order = new SchedulingOrder(orderId, "SO-1", "FG-1", BigDecimal.TEN,
                LocalDate.of(2030, 1, 1), "NORMAL", "UNFROZEN", null, operations);
        ScheduleProblem problem = new ScheduleProblem(TENANT_ID.toString(), SANDBOX_ID, null,
                null, "FORWARD", List.of(order), List.of(), List.of(), List.of(), List.of());
        return new ScheduleProblemAssembler.AssembledProblem(TENANT_ID, SANDBOX_ID, problem,
                List.of(orderId));
    }

    /**
     * 按 portfolioProfile 返回固定分数的引擎替身。
     */
    private static final class ProfileAdapter implements SolverAdapter {

        private final Map<String, Long> softScores = new LinkedHashMap<>();

        private final List<ScorePoint> history;

        private volatile boolean failAll;

        ProfileAdapter(List<ScorePoint> history) {
            this.history = history;
        }

        @Override
        public SolverEngine engine() {
            return SolverEngine.TIMEFOLD;
        }

        @Override
        public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
            if (failAll) {
                throw new IllegalStateException("scripted all failure");
            }
            String profile = String.valueOf(config.parameters().getOrDefault("portfolioProfile", "baseline"));
            long soft = softScores.getOrDefault(profile, -10L);
            return buildSolution(problem.orders().get(0).orderId(), 3, profile, soft, history);
        }

        ScheduleSolution buildSolution(UUID orderId, int operationCount, String profile,
                                       long soft, List<ScorePoint> points) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("slotWaitMs", 3L);
            extra.put("profile", profile);
            SolveMetrics metrics = new SolveMetrics(100L, "TIME_LIMIT", points, operationCount, extra);
            return new ScheduleSolution("SOLVED",
                    new ScoreFact(BigDecimal.ZERO, BigDecimal.valueOf(soft), "0hard/" + soft + "soft"),
                    assignments(orderId, operationCount), List.of(), metrics, "ok");
        }

        private static List<TaskAssignment> assignments(UUID orderId, int count) {
            OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
            List<TaskAssignment> assignments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                OffsetDateTime begin = start.plusMinutes(index);
                assignments.add(new TaskAssignment(null, orderId, "SO-1", index, "工序" + index,
                        null, null, "R-1", List.of(), begin, begin, begin, begin.plusMinutes(1),
                        BigDecimal.ONE, index, "PLANNED", false, 1));
            }
            return assignments;
        }
    }
}
