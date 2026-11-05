package com.hengce.aps.solver.timefold.bench;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.timefold.TimefoldModelBuilder;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S20「MIN_MAKESPAN 单步不结束」的回归测试。
 *
 * <p>根因不是 min/max 聚合本身，而是 Local Search 的单步循环：S20 只有 2 个任务、各占一条主资源，
 * makespan 已经在物理下界（单任务自身 130 分钟），此时<strong>任何候选 move 都只会让分数严格变差</strong>，
 * 于是没有任何 move 被接受；而默认邻域是随机选择序（never-ending），forager 的
 * {@code isQuitEarly()} 永远不会为真，单步就退不出来。{@code stepCountLimit} 只在步边界比较
 * {@code nextStepIndex}，因此永远等不到生效时机；只有墙钟 / 无改进这类时间型termination会在单步内被检查。</p>
 *
 * <p>因此本测试锁定两条可用预算口径，两者都必须让 S20 在有限时间内返回确定终态：</p>
 * <ul>
 *   <li>题面自带的墙钟预算（3s 上限 + 2s 无改进）；</li>
 *   <li>步数预算 + 墙钟上限叠加（纯步数模式在 S20 上不可用，见上）。</li>
 * </ul>
 *
 * <p>每个用例都在独立线程里跑，并有硬超时兜底；超时会 {@code terminateEarly()} 并让测试失败，
 * 不会在测试进程里留下空转线程。</p>
 */
class MinMakespanTerminationRegressionTest {

    private static final String CASE_ID = "S20-infeasible-missing-routing";

    private static final long HARD_TIMEOUT_SECONDS = 30L;

    /**
     * 题面自带预算（{@code budget.timeoutSeconds=3, unimprovedSecondsSpentLimit=2}）必须返回终态。
     */
    @Test
    void wallClockBudgetReturnsTerminalState() throws Exception {
        BenchCase benchCase = loadCase();
        SchedulePlan result = solveWithin(benchCase, new TerminationConfig()
                .withSpentLimit(Duration.ofSeconds(3L))
                .withUnimprovedSecondsSpentLimit(2L));
        assertS20Optimum(result);
    }

    /**
     * 步数预算只有在叠加墙钟上限时才可用；纯 {@code stepCountLimit} 会在单步内永不返回。
     */
    @Test
    void stepBudgetWithWallClockCapReturnsTerminalState() throws Exception {
        BenchCase benchCase = loadCase();
        SchedulePlan result = solveWithin(benchCase, new TerminationConfig()
                .withStepCountLimit(50)
                .withSpentLimit(Duration.ofSeconds(3L)));
        assertS20Optimum(result);
    }

    private static BenchCase loadCase() throws URISyntaxException {
        Path casesDir = Path.of(Objects.requireNonNull(
                MinMakespanTerminationRegressionTest.class.getResource("/bench/cases")).toURI());
        return BenchCase.loadAll(casesDir).stream()
                .filter(item -> item.caseId().equals(CASE_ID))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少题面: " + CASE_ID));
    }

    private static SchedulePlan solveWithin(BenchCase benchCase, TerminationConfig termination)
            throws Exception {
        ScheduleProblem problem = BenchProblemFactory.materialize(benchCase);
        SolverConfig config = new SolverConfig()
                .withSolutionClass(SchedulePlan.class)
                .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                .withConstraintProviderClass(ApsConstraintProvider.class)
                .withRandomSeed(benchCase.seed())
                .withTerminationConfig(termination);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "s20-regression-solver");
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<Solver<SchedulePlan>> solverRef = new AtomicReference<>();
        Future<SchedulePlan> future = executor.submit(() -> {
            // SoftConstraintWeights 是 ThreadLocal，且在 buildSolver() 时被约束提供者读取：
            // 必须与构建、solve 在同一线程激活，否则所有软约束权重退化为 0。
            SoftConstraintWeights.activate(SoftConstraintWeights.defaults());
            try {
                Solver<SchedulePlan> solver = SolverFactory.<SchedulePlan>create(config).buildSolver();
                solverRef.set(solver);
                return solver.solve(TimefoldModelBuilder.build(problem));
            } finally {
                SoftConstraintWeights.disable();
            }
        });
        try {
            return future.get(HARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            Solver<SchedulePlan> solver = solverRef.get();
            if (solver != null) {
                solver.terminateEarly();
            }
            future.get(5L, TimeUnit.SECONDS);
            fail("求解在 " + HARD_TIMEOUT_SECONDS + "s 内没有返回终态（单步循环挂住）：" + termination);
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * S20 的最优解：2 个任务都在 2026-01-05T00:00 起、各自 130 分钟，makespan = 130 分钟，
     * MIN_MAKESPAN 至少贡献 -1300 软分；其余软约束只会让总分更低。
     */
    private static void assertS20Optimum(SchedulePlan plan) {
        HardSoftScore score = plan.getScore();
        long assigned = plan.getTasks().stream()
                .filter(task -> task.getStartTime() != null)
                .count();
        assertEquals(2L, plan.getTasks().size(), "S20 只应物化 2 个任务");
        assertEquals(2L, assigned, "两个任务都必须被排位");
        assertEquals(0, score.hardScore(), "S20 的可行解 hard 必须为 0");
        assertTrue(score.softScore() <= -1300L,
                "makespan 已在物理下界（130 分钟），软分应不高于 -1300，实际 " + score.softScore());
    }
}
