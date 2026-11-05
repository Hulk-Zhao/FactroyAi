package com.hengce.aps.service.schedule.verification;

import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * C52/C53 共用的“不落库验证求解”接缝。
 *
 * <p>职责只有两件事：调用真实 {@link SolverAdapter}，把引擎输出翻译成
 * {@link VerificationOutcome}。<b>绝不</b>调用 {@code ScheduleResultWriter}，也不写
 * {@code plan_version}/{@code task}/{@code orders}/{@code solve_task}。</p>
 *
 * <p>预算耗尽的判定必须单独表达：求解器 {@code TIMEOUT}，或 {@code SOLVED} 但因
 * {@code TIME_LIMIT} 仍有未分配工序，一律返回 {@link VerificationStatus#OUT_OF_BUDGET}，
 * 不得冒充“不可行”。</p>
 */
@Service
public class VerificationSolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerificationSolver.class);

    private static final String STATUS_SOLVED = "SOLVED";

    private static final String STATUS_INFEASIBLE = "INFEASIBLE";

    private static final String STATUS_TIMEOUT = "TIMEOUT";

    private static final String STATUS_FAILED = "FAILED";

    private static final String TERMINATION_TIME_LIMIT = "TIME_LIMIT";

    private static final String BUSY_REASON = "SOLVER_BUSY";

    private final ObjectProvider<SolverAdapter> solverAdapters;

    /**
     * 构造接缝。
     *
     * @param solverAdapters 求解器适配器（可空；缺失时返回 UNVERIFIED）
     */
    public VerificationSolver(ObjectProvider<SolverAdapter> solverAdapters) {
        this.solverAdapters = solverAdapters;
    }

    /**
     * 对整个题面做一次只读验证求解。
     *
     * @param problem 题面
     * @param config 求解配置（timeoutSeconds 已由调用方按预算裁剪）
     * @return 验证结果；任何情况下 {@code persisted=false}
     */
    public VerificationOutcome solve(ScheduleProblem problem, SolveConfig config) {
        return solve(problem, config, null);
    }

    /**
     * 对指定订单做一次只读验证求解。
     *
     * @param problem 题面
     * @param config 求解配置
     * @param targetOrderIds 目标订单；为空表示整个题面
     * @return 验证结果；任何情况下 {@code persisted=false}
     */
    public VerificationOutcome solve(ScheduleProblem problem, SolveConfig config,
                                     Set<UUID> targetOrderIds) {
        Objects.requireNonNull(problem, "problem 不能为空");
        Objects.requireNonNull(config, "config 不能为空");
        Set<UUID> targets = targetOrderIds == null ? Set.of() : Set.copyOf(targetOrderIds);
        int totalTaskCount = totalOperations(problem, targets);
        long startedAt = System.nanoTime();
        SolverAdapter adapter = selectAdapter();
        if (adapter == null) {
            return unverified("timefold", totalTaskCount, elapsedMillis(startedAt),
                    "求解器未注册；本次未做容量验证");
        }
        ScheduleSolution solution;
        try {
            solution = adapter.solve(problem, config);
        } catch (RuntimeException exception) {
            LOGGER.warn("验证求解调用失败（降级为 UNVERIFIED）", exception);
            return unverified(adapter.engine().getCode(), totalTaskCount, elapsedMillis(startedAt),
                    "求解器调用失败：" + exception.getMessage());
        }
        if (solution == null) {
            return unverified(adapter.engine().getCode(), totalTaskCount, elapsedMillis(startedAt),
                    "求解器未返回结果（null）");
        }
        return translate(solution, problem, targets, totalTaskCount,
                elapsedMillis(startedAt), config);
    }

    private VerificationOutcome translate(ScheduleSolution solution, ScheduleProblem problem,
                                          Set<UUID> targets, int totalTaskCount,
                                          long elapsedMs, SolveConfig config) {
        SolveMetrics metrics = solution.metrics();
        long durationMs = metrics == null ? elapsedMs : metrics.durationMs();
        String terminationReason = metrics == null ? null : metrics.terminationReason();
        BigDecimal hardScore = solution.score() == null ? null : solution.score().hardScore();
        BigDecimal softScore = solution.score() == null ? null : solution.score().softScore();
        Long seed = config.randomSeed();
        String engine = config.engine() == null ? "timefold" : config.engine();
        Counts counts = countAssignments(solution, problem, targets);
        String status = solution.status() == null ? STATUS_FAILED : solution.status();

        if (STATUS_TIMEOUT.equalsIgnoreCase(status)) {
            return new VerificationOutcome(VerificationStatus.OUT_OF_BUDGET, engine, seed,
                    hardScore, softScore, durationMs, terminationReason,
                    totalTaskCount, counts.assigned(), null, solution.assignments(),
                    solution.justifications(), false, false,
                    "验证求解在 " + config.timeoutSeconds() + " 秒预算内未完成（已分配 "
                            + counts.assigned() + "/" + totalTaskCount + " 道工序）；预算耗尽不等于不可行");
        }
        if (STATUS_FAILED.equalsIgnoreCase(status)) {
            String reason = extraReason(metrics);
            String message = BUSY_REASON.equals(reason)
                    ? "求解槽位忙，本次未执行验证求解"
                    : "求解器失败：" + (solution.message() == null ? "未知原因" : solution.message());
            return unverified(engine, totalTaskCount, durationMs, message);
        }
        if (STATUS_INFEASIBLE.equalsIgnoreCase(status)) {
            return new VerificationOutcome(VerificationStatus.INFEASIBLE, engine, seed,
                    hardScore, softScore, durationMs, terminationReason,
                    totalTaskCount, counts.assigned(), counts.unassigned(), solution.assignments(),
                    solution.justifications(), false, false,
                    "验证求解判定不可行（硬分 "
                            + (hardScore == null ? "-" : hardScore.toPlainString()) + "，已分配 "
                            + counts.assigned() + "/" + totalTaskCount + " 道工序）");
        }
        if (!STATUS_SOLVED.equalsIgnoreCase(status)) {
            return unverified(engine, totalTaskCount, durationMs, "求解器返回未知状态 " + status);
        }
        if (hardScore != null && hardScore.compareTo(BigDecimal.ZERO) < 0) {
            return new VerificationOutcome(VerificationStatus.INFEASIBLE, engine, seed,
                    hardScore, softScore, durationMs, terminationReason,
                    totalTaskCount, counts.assigned(), counts.unassigned(), solution.assignments(),
                    solution.justifications(), false, false,
                    "验证求解存在硬约束违反（硬分 " + hardScore.toPlainString() + "）");
        }
        if (counts.unassigned() > 0) {
            VerificationStatus statusOrBudget = TERMINATION_TIME_LIMIT.equalsIgnoreCase(terminationReason)
                    ? VerificationStatus.OUT_OF_BUDGET
                    : VerificationStatus.INFEASIBLE;
            String message = statusOrBudget == VerificationStatus.OUT_OF_BUDGET
                    ? "验证求解在预算内未分配全部工序（" + counts.assigned() + "/"
                            + totalTaskCount + "）；预算耗尽不等于不可行"
                    : "验证求解未分配全部工序（" + counts.assigned() + "/" + totalTaskCount + "）";
            return new VerificationOutcome(statusOrBudget, engine, seed, hardScore, softScore,
                    durationMs, terminationReason, totalTaskCount, counts.assigned(), null,
                    solution.assignments(), solution.justifications(), false, false, message);
        }
        return new VerificationOutcome(VerificationStatus.FEASIBLE, engine, seed, hardScore,
                softScore, durationMs, terminationReason, totalTaskCount, counts.assigned(), 0,
                solution.assignments(), solution.justifications(), false, false,
                "验证求解可行：hard=" + (hardScore == null ? "-" : hardScore.toPlainString())
                        + "，目标工序 " + counts.assigned() + "/" + totalTaskCount + " 全部已分配");
    }

    private VerificationOutcome unverified(String engine, int totalTaskCount, long durationMs,
                                           String message) {
        return new VerificationOutcome(VerificationStatus.UNVERIFIED, engine, null, null, null,
                durationMs, null, totalTaskCount, 0, null, List.of(), List.of(), false, false,
                message);
    }

    private Counts countAssignments(ScheduleSolution solution, ScheduleProblem problem,
                                    Set<UUID> targets) {
        Set<String> assignedKeys = new HashSet<>();
        for (TaskAssignment assignment : solution.assignments()) {
            if (assignment.endTime() == null) {
                continue;
            }
            if (!targets.isEmpty() && !targets.contains(assignment.orderId())) {
                continue;
            }
            assignedKeys.add(assignment.orderId() + "#" + assignment.operationIndex());
        }
        int total = 0;
        for (SchedulingOrder order : problem.orders()) {
            if (!targets.isEmpty() && !targets.contains(order.orderId())) {
                continue;
            }
            total += order.operations().size();
        }
        int assigned = Math.min(total, assignedKeys.size());
        return new Counts(total, assigned);
    }

    private static int totalOperations(ScheduleProblem problem, Set<UUID> targets) {
        int total = 0;
        for (SchedulingOrder order : problem.orders()) {
            if (!targets.isEmpty() && !targets.contains(order.orderId())) {
                continue;
            }
            total += order.operations().size();
        }
        return total;
    }

    private SolverAdapter selectAdapter() {
        List<SolverAdapter> adapters = solverAdapters.orderedStream().toList();
        if (adapters.isEmpty()) {
            return null;
        }
        return adapters.stream()
                .filter(adapter -> adapter.engine() == SolverEngine.TIMEFOLD)
                .findFirst()
                .orElse(adapters.get(0));
    }

    private static String extraReason(SolveMetrics metrics) {
        if (metrics == null || metrics.extra() == null) {
            return null;
        }
        Map<String, Object> extra = metrics.extra();
        Object reason = extra.get("engineFallbackReason");
        return reason == null ? null : String.valueOf(reason);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private record Counts(int total, int assigned) {

        int unassigned() {
            return Math.max(0, total - assigned);
        }
    }
}
