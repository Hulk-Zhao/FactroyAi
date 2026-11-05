package com.hengce.aps.service.schedule.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.service.schedule.ScheduleService;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.ScorePoint;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A1 Portfolio 运行时单测：选优、gating、失败隔离、超时与取消。
 */
class SolvePortfolioCoordinatorTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void 三候选并行且赢家是软分最好的那个() {
        ScheduleProblem problem = problem(3);
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.softScores.put("baseline", -100L);
        adapter.softScores.put("subchain", -50L);
        adapter.softScores.put("ruin", -75L);
        adapter.barrier = new java.util.concurrent.CountDownLatch(3);
        SolvePortfolioCoordinator coordinator = coordinator(8, 8 * GIB, 3, 800);

        PortfolioOutcome outcome = coordinator.run(problem, config(3), adapter,
                new SolvePortfolioCoordinator.PortfolioContext(11L, 3, () -> false, null));

        assertNotNull(outcome.solution(), "应选出赢家");
        assertEquals("subchain", outcome.winnerProfile(), "软分 -50 的 subchain 应胜出");
        assertEquals(3, adapter.maxActive.get(), "三候选必须并行执行");
        Map<String, Object> observation = outcome.observation();
        assertEquals(true, observation.get("enabled"));
        assertEquals(3, observation.get("candidateCount"));
        assertEquals("subchain", observation.get("winnerProfile"));
        assertEquals(false, observation.get("degraded"));
        assertEquals(3, ((List<?>) observation.get("candidates")).size());
        Map<String, Object> baseline = candidate(observation, "baseline");
        assertEquals("SUCCEEDED", baseline.get("status"));
        assertEquals(false, baseline.get("winner"));
        assertFalse(baseline.containsKey("score"), "C54 候选字段扁平，不得嵌套 score");
        assertFalse(baseline.containsKey("assignedTaskCount"), "C54 契约外字段不得出现");
        assertEquals(0, new java.math.BigDecimal("-100").compareTo((java.math.BigDecimal) baseline.get("softScore")));
        Map<String, Object> subchain = candidate(observation, "subchain");
        assertTrue((Boolean) subchain.get("winner"));
        assertEquals(0, new java.math.BigDecimal("-50").compareTo((java.math.BigDecimal) subchain.get("softScore")));
        assertNotNull(subchain.get("durationMs"));
        assertNotNull(subchain.get("terminationReason"));
        assertTrue((Long) observation.get("slotWaitMs") >= 0);
        assertNotNull(observation.get("generatedAt"));
    }

    @Test
    void 任务规模超阈值时降为单候选并如实标记降级() {
        ScheduleProblem problem = problem(900);
        ScriptedAdapter adapter = new ScriptedAdapter();
        SolvePortfolioCoordinator coordinator = coordinator(8, 8 * GIB, 3, 800);

        PortfolioOutcome outcome = coordinator.run(problem, config(5), adapter,
                new SolvePortfolioCoordinator.PortfolioContext(12L, 900, () -> false, null));

        Map<String, Object> observation = outcome.observation();
        assertEquals(true, observation.get("degraded"));
        assertEquals("TASK_SCALE", observation.get("degradedReason"));
        // C54：candidateCount 包含被拒候选；实际开跑数由 status 体现。
        assertEquals(3, observation.get("candidateCount"));
        assertEquals(1, adapter.maxActive.get(), "只允许 1 个候选开跑");
        List<?> candidates = (List<?>) observation.get("candidates");
        assertEquals(3, candidates.size());
        assertEquals("SUCCEEDED", ((Map<?, ?>) candidates.get(0)).get("status"));
        assertEquals("REJECTED", ((Map<?, ?>) candidates.get(1)).get("status"));
        assertEquals("REJECTED_BY_ADMISSION", ((Map<?, ?>) candidates.get(1)).get("failureReason"));
        assertEquals("REJECTED", ((Map<?, ?>) candidates.get(2)).get("status"));
    }

    @Test
    void 内存不足时由内存预算降级() {
        SolvePortfolioSettings settings = SolvePortfolioSettings.forTest(true, 3, 5, 800, 1.0, true, 0);
        PortfolioAdmission.Admission admission = PortfolioAdmission.admit(settings, 300, 8, GIB);
        assertEquals(1, admission.effectiveCandidates());
        assertTrue(admission.degraded());
        assertEquals("MEMORY_BUDGET", admission.reason().name());
    }

    @Test
    void 单候选失败被隔离不影响其它候选() {
        ScheduleProblem problem = problem(3);
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.failProfiles.add("subchain");
        adapter.softScores.put("baseline", -100L);
        adapter.softScores.put("ruin", -20L);
        SolvePortfolioCoordinator coordinator = coordinator(8, 8 * GIB, 3, 800);

        PortfolioOutcome outcome = coordinator.run(problem, config(3), adapter,
                new SolvePortfolioCoordinator.PortfolioContext(13L, 3, () -> false, null));

        assertNotNull(outcome.solution(), "其余候选仍应产出赢家");
        assertEquals("ruin", outcome.winnerProfile(), "ruin 软分 -20 最优");
        Map<String, Object> observation = outcome.observation();
        assertEquals(true, outcome.observation().get("degraded"), "部分失败必须如实标记降级");
        assertEquals("UNKNOWN", outcome.observation().get("degradedReason"),
                "9 值枚举没有 ENGINE_ERROR，具体原因留在 candidates[].failureReason");
        Map<String, Object> failed = candidate(observation, "subchain");
        assertEquals("FAILED", failed.get("status"));
        assertEquals("ENGINE_ERROR", failed.get("failureReason"));
    }

    @Test
    void 全部候选失败才判定无解() {
        ScheduleProblem problem = problem(3);
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.failProfiles.addAll(List.of("baseline", "subchain", "ruin"));
        SolvePortfolioCoordinator coordinator = coordinator(8, 8 * GIB, 3, 800);

        PortfolioOutcome outcome = coordinator.run(problem, config(3), adapter,
                new SolvePortfolioCoordinator.PortfolioContext(14L, 3, () -> false, null));

        assertNull(outcome.solution());
        assertFalse(outcome.cancelled());
        assertEquals("ENGINE_ERROR", outcome.failureReason());
        assertEquals(true, outcome.observation().get("degraded"));
        assertEquals("UNKNOWN", outcome.observation().get("degradedReason"));
    }

    @Test
    void 整体预算耗尽时候选全部标记超时() {
        ScheduleProblem problem = problem(3);
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.sleepMillis.put("baseline", 5_000L);
        adapter.sleepMillis.put("subchain", 5_000L);
        adapter.sleepMillis.put("ruin", 5_000L);
        SolvePortfolioCoordinator coordinator = coordinator(8, 8 * GIB, 3, 800);

        PortfolioOutcome outcome = coordinator.run(problem, config(1), adapter,
                new SolvePortfolioCoordinator.PortfolioContext(15L, 3, () -> false, null));

        assertNull(outcome.solution());
        assertTrue(outcome.timedOut());
        assertEquals("TIMEOUT", outcome.failureReason());
        List<?> candidates = (List<?>) outcome.observation().get("candidates");
        assertEquals(3, candidates.size());
        for (Object item : candidates) {
            assertEquals("TIMEOUT", ((Map<?, ?>) item).get("status"));
            assertEquals("TIMEOUT", ((Map<?, ?>) item).get("failureReason"));
        }
        assertEquals("UNKNOWN", outcome.observation().get("degradedReason"),
                "C54 的 9 值枚举没有 TIMEOUT，超时只能落到 UNKNOWN");
    }

    @Test
    void 取消会穿透所有候选且线程回落() throws Exception {
        ScheduleProblem problem = problem(3);
        ScriptedAdapter adapter = new ScriptedAdapter();
        adapter.loopForever = true;
        SolvePortfolioCoordinator coordinator = coordinator(8, 8 * GIB, 3, 800);
        AtomicLong stopAt = new AtomicLong(System.nanoTime() + 150_000_000L);
        PortfolioOutcome[] holder = new PortfolioOutcome[1];
        Thread worker = new Thread(() -> holder[0] = coordinator.run(problem, config(10), adapter,
                new SolvePortfolioCoordinator.PortfolioContext(16L, 3,
                        () -> System.nanoTime() >= stopAt.get(), null)), "portfolio-cancel-test");
        worker.start();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "运行线程应退出");
        assertNotNull(holder[0]);
        assertTrue(holder[0].cancelled(), "应返回取消标记");
        long iterationsAtCancel = adapter.iterations.get();
        Thread.sleep(300);
        assertEquals(iterationsAtCancel, adapter.iterations.get(), "候选线程不应继续迭代");
        assertTrue(awaitNoPortfolioThreads(3_000), "取消后不应残留 aps-portfolio-* 线程");
    }

    private static boolean awaitNoPortfolioThreads(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        do {
            boolean any = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive()
                            && thread.getName().startsWith("aps-portfolio-"));
            if (!any) {
                return true;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static SolvePortfolioCoordinator coordinator(
            int processors, long heap, int candidates, int maxTasks) {
        SolvePortfolioSettings settings = SolvePortfolioSettings.forTest(
                true, candidates, 5, maxTasks, 1.0, true, 0);
        return new SolvePortfolioCoordinator(settings, () -> processors, () -> heap);
    }

    private static Map<String, Object> candidate(Map<String, Object> observation, String profile) {
        return ((List<?>) observation.get("candidates")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> profile.equals(item.get("profile")))
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    item.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    return copy;
                })
                .findFirst()
                .orElseThrow();
    }

    private static SolveConfig config(int timeoutSeconds) {
        return new SolveConfig("timefold", timeoutSeconds, 0, "LATE_ACCEPTANCE",
                7L, 0L, 10, "FORWARD", true, Map.of());
    }

    private static ScheduleProblem problem(int operationCount) {
        UUID orderId = UUID.randomUUID();
        UUID routingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        List<SchedulingOperation> operations = new ArrayList<>(operationCount);
        for (int index = 0; index < operationCount; index++) {
            operations.add(new SchedulingOperation(index, "OP" + index, "工序" + index,
                    null, BigDecimal.valueOf(60), BigDecimal.ZERO, resourceId, null,
                    List.of(), false, List.of()));
        }
        SchedulingOrder order = new SchedulingOrder(orderId, "SO-1", "FG-1", null,
                BigDecimal.valueOf(operationCount), LocalDate.of(2026, 11, 10), "NORMAL",
                "UNFROZEN", routingId, operations, null);
        SchedulingResource resource = new SchedulingResource(resourceId, "R-1", "PRIMARY",
                null, "UNIT_PER_HOUR", BigDecimal.valueOf(10), 1, BigDecimal.ONE, null,
                "ACTIVE", List.of());
        return new ScheduleProblem(UUID.randomUUID().toString(), UUID.randomUUID(), 100L, null, "FORWARD",
                List.of(order), List.of(resource), List.of(), List.of(), List.of(),
                LocalDateTime.of(2026, 11, 5, 0, 0), List.of());
    }

    /**
     * 受控替身：按画像返回不同分数；maxActive 记录真实并行度。
     */
    private static final class ScriptedAdapter implements SolverAdapter {

        private final Map<String, Long> softScores = new ConcurrentHashMap<>();

        private final Map<String, Long> sleepMillis = new ConcurrentHashMap<>();

        private final java.util.Set<String> failProfiles = ConcurrentHashMap.newKeySet();

        private final AtomicInteger active = new AtomicInteger();

        private final AtomicInteger maxActive = new AtomicInteger();

        private final AtomicLong iterations = new AtomicLong();

        private volatile boolean loopForever;

        private volatile java.util.concurrent.CountDownLatch barrier;

        @Override
        public SolverEngine engine() {
            return SolverEngine.TIMEFOLD;
        }

        @Override
        public ScheduleSolution solve(ScheduleProblem problem, SolveConfig config) {
            String profile = String.valueOf(config.parameters().getOrDefault("portfolioProfile", "baseline"));
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            java.util.concurrent.CountDownLatch gate = barrier;
            if (gate != null) {
                gate.countDown();
                try {
                    gate.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (loopForever) {
                    while (true) {
                        iterations.incrementAndGet();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted", exception);
                        }
                    }
                }
                long sleep = sleepMillis.getOrDefault(profile, 0L);
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", exception);
                    }
                }
                if (failProfiles.contains(profile)) {
                    throw new IllegalStateException("scripted failure: " + profile);
                }
                long soft = softScores.getOrDefault(profile, -10L);
                List<ScorePoint> history = List.of(
                        new ScorePoint(5, 0, BigDecimal.ZERO, BigDecimal.valueOf(soft - 10)),
                        new ScorePoint(20, 5, BigDecimal.ZERO, BigDecimal.valueOf(soft)));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("slotWaitMs", 3L);
                SolveMetrics metrics = new SolveMetrics(20L, "TERMINATED", history,
                        problem.orders().get(0).operations().size(), extra);
                return new ScheduleSolution("SOLVED",
                        new ScoreFact(BigDecimal.ZERO, BigDecimal.valueOf(soft), "0hard/" + soft + "soft"),
                        assignments(problem, problem.orders().get(0).operations().size()),
                        List.of(), metrics, "scripted");
            } finally {
                active.decrementAndGet();
            }
        }

        private static List<TaskAssignment> assignments(ScheduleProblem problem, int count) {
            UUID orderId = problem.orders().get(0).orderId();
            UUID resourceId = problem.resources().get(0).resourceId();
            OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
            List<TaskAssignment> assignments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                OffsetDateTime begin = start.plusMinutes(index);
                assignments.add(new TaskAssignment(null, orderId, "SO-1", index,
                        "工序" + index, null, resourceId, "R-1", List.of(),
                        begin, begin, begin, begin.plusMinutes(1), BigDecimal.ONE,
                        index, "PLANNED", false, 1));
            }
            return assignments;
        }
    }
}
