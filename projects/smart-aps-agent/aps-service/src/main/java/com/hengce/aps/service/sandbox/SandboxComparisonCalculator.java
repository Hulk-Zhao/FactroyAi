package com.hengce.aps.service.sandbox;

import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository.CompareTaskRow;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 场景对比计算器（C32 §25.3.3）。
 *
 * <p>匹配键为 {@code (orderNo, operationIndex)}；只在被比较方存在计入 ADDED，只在基线方存在计入
 * REMOVED，两侧都存在但 {@code startTime/endTime/resourceCode} 任一不同计入 MOVED。</p>
 *
 * <p>口径说明（契约只给定义、未给公式的部分）：</p>
 * <ul>
 *   <li>{@code dueDateDeltaMinutes}：每侧取所有有交期任务的 {@code 任务结束 - 交期当日业务时区 24:00} 分钟差
 *       的最大值，再作差；无交期任务不计。截止点用业务时区（{@code aps.business.time-zone}，默认
 *       {@code Asia/Shanghai}）计算，不使用部署机默认时区。</li>
 *   <li>{@code utilizationDelta}：每侧以 {@code max(end) - min(start)} 为观察窗口，逐资源累加占用分钟，
 *       资源利用率 = 占用分钟 / 窗口分钟；对非空资源取均值，保留 3 位小数。</li>
 *   <li>{@code setupCountDelta}：含换型时段（{@code setup_start_time} 非空）的任务数之差。</li>
 * </ul>
 */
public final class SandboxComparisonCalculator {

    /**
     * 变更明细上限。
     */
    public static final int CHANGED_TASK_LIMIT = 200;

    private SandboxComparisonCalculator() {
    }

    /**
     * 计算两侧差异。
     *
     * @param sandboxId 被比较方
     * @param targetId 基线方
     * @param comparedTasks 被比较方任务
     * @param targetTasks 基线方任务
     * @param businessZone 业务时区，用于交期当日 24:00 截止点
     * @return 对比结果
     */
    public static SandboxCompareView compare(UUID sandboxId, UUID targetId,
                                             List<CompareTaskRow> comparedTasks,
                                             List<CompareTaskRow> targetTasks,
                                             ZoneId businessZone) {
        Map<TaskKey, CompareTaskRow> compared = index(comparedTasks);
        Map<TaskKey, CompareTaskRow> target = index(targetTasks);

        int added = 0;
        int removed = 0;
        int moved = 0;
        List<SandboxCompareView.ChangedTaskView> changed = new ArrayList<>();
        for (Map.Entry<TaskKey, CompareTaskRow> entry : compared.entrySet()) {
            CompareTaskRow baseline = target.get(entry.getKey());
            if (baseline == null) {
                added++;
                changed.add(changedView(entry.getValue(), "ADDED"));
            } else if (isMoved(entry.getValue(), baseline)) {
                moved++;
                changed.add(changedView(entry.getValue(), "MOVED"));
            }
        }
        for (Map.Entry<TaskKey, CompareTaskRow> entry : target.entrySet()) {
            if (!compared.containsKey(entry.getKey())) {
                removed++;
                changed.add(changedView(entry.getValue(), "REMOVED"));
            }
        }
        changed.sort((left, right) -> {
            int byType = Integer.compare(changeRank(left.changeType()), changeRank(right.changeType()));
            if (byType != 0) {
                return byType;
            }
            int byOrder = nullSafe(left.orderNo()).compareTo(nullSafe(right.orderNo()));
            return byOrder != 0 ? byOrder : Long.compare(left.taskId(), right.taskId());
        });
        boolean truncated = changed.size() > CHANGED_TASK_LIMIT;
        List<SandboxCompareView.ChangedTaskView> limited = truncated
                ? List.copyOf(changed.subList(0, CHANGED_TASK_LIMIT))
                : List.copyOf(changed);

        return new SandboxCompareView(sandboxId, targetId,
                maxLatenessMinutes(comparedTasks, businessZone) - maxLatenessMinutes(targetTasks, businessZone),
                round3(averageUtilization(comparedTasks) - averageUtilization(targetTasks)),
                countSetup(comparedTasks) - countSetup(targetTasks),
                added, removed, moved, limited, truncated);
    }

    private static Map<TaskKey, CompareTaskRow> index(List<CompareTaskRow> rows) {
        Map<TaskKey, CompareTaskRow> result = new LinkedHashMap<>();
        for (CompareTaskRow row : rows) {
            result.putIfAbsent(new TaskKey(row.orderNo(), row.operationIndex()), row);
        }
        return result;
    }

    private static boolean isMoved(CompareTaskRow compared, CompareTaskRow target) {
        return !Objects.equals(compared.startTime(), target.startTime())
                || !Objects.equals(compared.endTime(), target.endTime())
                || !Objects.equals(compared.resourceCode(), target.resourceCode());
    }

    private static SandboxCompareView.ChangedTaskView changedView(CompareTaskRow row, String changeType) {
        return new SandboxCompareView.ChangedTaskView(row.taskId(), row.orderNo(), row.resourceCode(),
                changeType);
    }

    static long maxLatenessMinutes(List<CompareTaskRow> rows, ZoneId businessZone) {
        long max = Long.MIN_VALUE;
        boolean found = false;
        for (CompareTaskRow row : rows) {
            if (row.endTime() == null || row.dueDate() == null) {
                continue;
            }
            OffsetDateTime dueEnd = dueEndOfDay(row.dueDate(), businessZone);
            long minutes = Duration.between(dueEnd, row.endTime()).toMinutes();
            max = found ? Math.max(max, minutes) : minutes;
            found = true;
        }
        return found ? max : 0L;
    }

    private static OffsetDateTime dueEndOfDay(LocalDate dueDate, ZoneId businessZone) {
        // 业务时区当日 24:00 = 次日 00:00，半开区间上界，与 [from,to) 语义一致
        return dueDate.plusDays(1).atStartOfDay(businessZone).toOffsetDateTime();
    }

    private static double averageUtilization(List<CompareTaskRow> rows) {
        Map<String, Double> utilization = resourceUtilization(rows);
        if (utilization.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Double value : utilization.values()) {
            sum += value;
        }
        return sum / utilization.size();
    }

    /**
     * 逐资源利用率，口径与本类 {@code utilizationDelta} 完全一致：
     * 观察窗口为单侧 {@code max(end) - min(start)}，资源利用率为占用分钟 / 窗口分钟（上限 1）。
     *
     * <p>供 Agent what-if 的“瓶颈变化”复用，避免第二套计算口径。</p>
     *
     * @param rows 单侧任务行
     * @return 资源编码 → 利用率；无任务时为空白 Map
     */
    public static Map<String, Double> resourceUtilization(List<CompareTaskRow> rows) {
        OffsetDateTime windowStart = null;
        OffsetDateTime windowEnd = null;
        Map<String, Long> busyMinutes = new HashMap<>();
        for (CompareTaskRow row : rows) {
            if (row.startTime() != null && row.endTime() != null) {
                windowStart = windowStart == null || row.startTime().isBefore(windowStart)
                        ? row.startTime() : windowStart;
                windowEnd = windowEnd == null || row.endTime().isAfter(windowEnd)
                        ? row.endTime() : windowEnd;
            }
            if (row.resourceCode() != null && row.startTime() != null && row.endTime() != null) {
                long minutes = Duration.between(row.startTime(), row.endTime()).toMinutes();
                busyMinutes.merge(row.resourceCode(), Math.max(minutes, 0L), Long::sum);
            }
        }
        if (busyMinutes.isEmpty() || windowStart == null || windowEnd == null) {
            return Map.of();
        }
        long windowMinutes = Duration.between(windowStart, windowEnd).toMinutes();
        if (windowMinutes <= 0) {
            return Map.of();
        }
        Map<String, Double> result = new HashMap<>();
        busyMinutes.forEach((resource, busy) ->
                result.put(resource, round3(Math.min(1.0, busy / (double) windowMinutes))));
        return result;
    }

    private static int countSetup(List<CompareTaskRow> rows) {
        return (int) rows.stream().filter(CompareTaskRow::hasSetup).count();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static int changeRank(String changeType) {
        return switch (changeType) {
            case "ADDED" -> 0;
            case "MOVED" -> 1;
            default -> 2;
        };
    }

    private record TaskKey(String orderNo, int operationIndex) {
    }
}
