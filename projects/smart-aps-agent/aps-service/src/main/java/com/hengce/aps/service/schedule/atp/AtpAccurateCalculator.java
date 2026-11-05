package com.hengce.aps.service.schedule.atp;

import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ACCURATE 结论计算器：只负责把求解器输出翻译为契约结论，不做任何持久化。
 *
 * <p>硬规则（C46）：只有 {@code SOLVED} 且假设订单全部工序均已分配时才给出 {@code VERIFIED} 日期；
 * {@code TIMEOUT}/{@code INFEASIBLE}/{@code FAILED} 一律不给日期，{@code accuracy=UNVERIFIED}。</p>
 */
public final class AtpAccurateCalculator {

    private static final String STATUS_SOLVED = "SOLVED";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final String STATUS_INFEASIBLE = "INFEASIBLE";

    private AtpAccurateCalculator() {
    }

    /**
     * 计算结论。
     *
     * @param solution       求解器输出
     * @param atpOrderId     假设订单主键
     * @param operationCount 假设订单工序数
     * @param requestedDate  请求交期
     * @param timeoutSeconds 限时秒数
     * @param elapsedMs      求解耗时
     * @param zone           业务时区
     * @return 结论
     */
    public static Outcome evaluate(
            ScheduleSolution solution,
            UUID atpOrderId,
            int operationCount,
            LocalDate requestedDate,
            int timeoutSeconds,
            long elapsedMs,
            ZoneId zone) {
        if (solution == null) {
            return Outcome.failed("求解器未返回结果（null）", elapsedMs);
        }
        String solverStatus = solution.status();
        List<TaskAssignment> assigned = solution.assignments().stream()
                .filter(a -> Objects.equals(atpOrderId, a.orderId()))
                .filter(a -> a.endTime() != null)
                .toList();
        int assignedCount = (int) assigned.stream().map(TaskAssignment::operationIndex).distinct().count();
        int unassignedCount = Math.max(0, operationCount - assignedCount);
        boolean timedOut = STATUS_TIMEOUT.equalsIgnoreCase(solverStatus);
        BigDecimal hardScore = solution.score() == null ? null : solution.score().hardScore();
        BigDecimal softScore = solution.score() == null ? null : solution.score().softScore();
        long solverDurationMs = solution.metrics() == null ? elapsedMs : solution.metrics().durationMs();

        if (STATUS_TIMEOUT.equalsIgnoreCase(solverStatus)) {
            return new Outcome(AtpStatus.TIMEOUT, AtpAccuracy.UNVERIFIED, null, null, null,
                    List.of(new AtpBottleneck("SOLVE_TIMEOUT", null, null, null, 0,
                            "限时 " + timeoutSeconds + " 秒内未得到完整可行解（假设订单已分配 "
                                    + assignedCount + "/" + operationCount + " 道工序，硬分 "
                                    + (hardScore == null ? "-" : hardScore.toPlainString())
                                    + "）；按契约不给出交期，不伪造日期")),
                    List.of(new AtpSuggestion("EXTEND_TIMEOUT", null, null, null, 0,
                            "将 timeoutSeconds 提高到 " + Math.min(30, timeoutSeconds * 2)
                                    + " 秒后重试；或请求 FAST 档获取可参考的估算交期")),
                    solverStatus, solverDurationMs, hardScore, softScore, assignedCount, unassignedCount,
                    true, "ACCURATE 限时求解超时，未形成可信交期");
        }
        if (STATUS_INFEASIBLE.equalsIgnoreCase(solverStatus)) {
            return new Outcome(AtpStatus.INFEASIBLE, AtpAccuracy.UNVERIFIED, null, null, null,
                    List.of(new AtpBottleneck("INFEASIBLE", null, null, null, 0,
                            "7 天求解窗口内无可行排程（硬约束无解或假设订单无法被完整分配）")),
                    List.of(new AtpSuggestion("INCREASE_CAPACITY", null, null, null, 0,
                            "检查资源日历有效期与 FROZEN 任务；增加班次/减少参与重排的已确认订单，"
                                    + "或放宽请求交期后重试")),
                    solverStatus, solverDurationMs, hardScore, softScore, assignedCount, unassignedCount,
                    false, "ACCURATE 求解不可行，未给出交期");
        }
        if (!STATUS_SOLVED.equalsIgnoreCase(solverStatus)) {
            return Outcome.failed("求解器状态 " + solverStatus + ": " + safeMessage(solution), elapsedMs);
        }
        if (assignedCount < operationCount) {
            return new Outcome(AtpStatus.TIMEOUT, AtpAccuracy.UNVERIFIED, null, null, null,
                    List.of(new AtpBottleneck("SOLVE_TIMEOUT", null, null, null, 0,
                            "求解器标记 SOLVED 但假设订单分配不完整（" + assignedCount + "/"
                                    + operationCount + "），按契约不给出交期")),
                    List.of(new AtpSuggestion("EXTEND_TIMEOUT", null, null, null, 0,
                            "请提高 timeoutSeconds 或检查求解器日志后重试")),
                    solverStatus, solverDurationMs, hardScore, softScore, assignedCount, unassignedCount,
                    true, "ACCURATE 结果不完整，未给出交期");
        }
        TaskAssignment critical = assigned.stream()
                .max(Comparator.comparing(TaskAssignment::endTime))
                .orElseThrow();
        OffsetDateTime planFrom = assigned.stream().map(AtpAccurateCalculator::effectiveStart)
                .min(Comparator.naturalOrder()).orElse(critical.startTime());
        OffsetDateTime planTo = critical.endTime();
        LocalDate deliveryDate = planTo.atZoneSameInstant(zone).toLocalDate();
        List<AtpBottleneck> bottlenecks = new ArrayList<>();
        bottlenecks.add(new AtpBottleneck("CRITICAL_OPERATION", critical.resourceCode(),
                critical.operationName(), critical.operationIndex(), 0,
                "决定交期的最后工序是 " + critical.operationName() + "（资源 "
                        + critical.resourceCode() + "，结束于 " + planTo + "）"));
        if (requestedDate != null && deliveryDate.isAfter(requestedDate)) {
            bottlenecks.add(new AtpBottleneck("DUE_DATE_MISS", critical.resourceCode(),
                    critical.operationName(), critical.operationIndex(), 0,
                    "最早可交付 " + deliveryDate + " 晚于请求交期 " + requestedDate));
        }
        List<AtpSuggestion> suggestions = new ArrayList<>();
        if (requestedDate != null && deliveryDate.isAfter(requestedDate)) {
            long lateDays = ChronoUnit.DAYS.between(requestedDate, deliveryDate);
            suggestions.add(new AtpSuggestion("ADJUST_PROMISED_DATE", null, null, null, 0,
                    "将承诺交期调整为 " + deliveryDate + "（比请求晚 " + lateDays
                            + " 天），或与客户协商顺延"));
        }
        if (critical.resourceCode() != null) {
            long criticalMinutes = Duration.between(effectiveStart(critical), critical.endTime()).toMinutes();
            suggestions.add(new AtpSuggestion("ADD_SHIFT", critical.resourceCode(),
                    critical.operationName(), critical.operationIndex(), (int) Math.max(0, criticalMinutes),
                    "关键工序占用资源 " + critical.resourceCode() + " 约 " + criticalMinutes
                            + " 分钟；在该资源上增开班次可缩短关键路径，需重新以 ACCURATE 验证"));
        }
        suggestions.add(new AtpSuggestion("LOCK_CAPACITY", null, null, null, 0,
                "该交期来自完整可行解；确认订单后建议锁定上述资源时段以保留产能（ATP 本身未占用产能）"));
        return new Outcome(AtpStatus.OK, AtpAccuracy.VERIFIED, deliveryDate, planFrom, planTo,
                List.copyOf(bottlenecks), List.copyOf(suggestions), solverStatus, solverDurationMs,
                hardScore, softScore, assignedCount, unassignedCount, false,
                "ACCURATE 限时求解得到完整可行解（终止原因 "
                        + (solution.metrics() == null ? "-" : solution.metrics().terminationReason())
                        + "）；该日期是可行上界，非最优性证明");
    }

    private static OffsetDateTime effectiveStart(TaskAssignment assignment) {
        if (assignment.setupStartTime() != null) {
            return assignment.setupStartTime();
        }
        return assignment.startTime();
    }

    private static String safeMessage(ScheduleSolution solution) {
        return solution.message() == null ? "" : solution.message();
    }

    /**
     * ACCURATE 结论。
     *
     * @param status         状态
     * @param accuracy       准确性口径
     * @param earliestDeliveryDate 最早交期
     * @param planFrom       排程起点
     * @param planTo         排程终点
     * @param bottlenecks    瓶颈
     * @param suggestions    建议
     * @param solverStatus   求解器状态
     * @param solverDurationMs 求解耗时
     * @param hardScore      硬分
     * @param softScore      软分
     * @param assignedCount  假设订单已分配工序数
     * @param unassignedCount 假设订单未分配工序数
     * @param timedOut       是否限时终止
     * @param message        结论说明
     */
    public record Outcome(
            AtpStatus status,
            AtpAccuracy accuracy,
            LocalDate earliestDeliveryDate,
            OffsetDateTime planFrom,
            OffsetDateTime planTo,
            List<AtpBottleneck> bottlenecks,
            List<AtpSuggestion> suggestions,
            String solverStatus,
            long solverDurationMs,
            BigDecimal hardScore,
            BigDecimal softScore,
            int assignedCount,
            int unassignedCount,
            boolean timedOut,
            String message) {

        static Outcome failed(String message, long elapsedMs) {
            return new Outcome(AtpStatus.FAILED, AtpAccuracy.UNVERIFIED, null, null, null,
                    List.of(new AtpBottleneck("SOLVER_FAILED", null, null, null, 0, message)),
                    List.of(), "FAILED", elapsedMs, null, null, 0, 0, false, message);
        }
    }
}
