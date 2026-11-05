package com.hengce.aps.service.schedule.atp;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.duration.OperationRunDuration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FAST 交期估算：构造启发式，不启动 Timefold。
 *
 * <p>算法与假设（契约 C46）：</p>
 * <ol>
 *   <li>按 {@code operationIndex} 升序串行推进（FS/0），忽略 SS/FF 重叠；对存在并行依赖的路线偏保守；</li>
 *   <li>工序时长按 C2 口径 {@code setup + run}，资源取工序主资源；主资源为空时取同组/全部资源中编码最小者，
 *       与 Timefold 建模型的资源解析保持一致；</li>
 *   <li>每个工序在资源可用时段内寻找最早可完整容纳的连续区间，并避让 BASELINE 场景已有任务；</li>
 *   <li>日历空列表按 7×24（C12）；超出给定 horizon 时返回“无可行日期”而不是伪造日期；</li>
 *   <li>不写 task/plan_version/orders，产能锁定只输出建议。</li>
 * </ol>
 */
public final class AtpFastEstimator {

    /** 建议最多保留条数。 */
    private static final int MAX_SUGGESTIONS = 6;

    /** 瓶颈最多保留条数。 */
    private static final int MAX_BOTTLENECKS = 5;

    /** 小于该秒数的等待视作可忽略。 */
    private static final long MIN_MEANINGFUL_WAIT_SECONDS = 60L;

    /** 换资源建议最多扫描的备选资源数，避免大量资源拖慢 P95。 */
    private static final int MAX_ALTERNATIVE_RESOURCES = 20;

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AtpFastEstimator() {
    }

    /**
     * 估算最早交期。
     *
     * @param order           假设订单（含工序）
     * @param resources       资源快照（含可用时段）
     * @param busyByResource  各资源已有任务占用区间，键为资源主键
     * @param readyAt         可开工时间
     * @param horizonEnd      估算最远时点
     * @param requestedDate   请求交期
     * @param allowSplitBatch 是否允许拆批建议
     * @param zone            业务时区
     * @return 估算结果
     */
    public static FastEstimate estimate(
            SchedulingOrder order,
            List<SchedulingResource> resources,
            Map<UUID, List<AvailabilityWindow>> busyByResource,
            OffsetDateTime readyAt,
            OffsetDateTime horizonEnd,
            LocalDate requestedDate,
            boolean allowSplitBatch,
            ZoneId zone) {
        if (order == null || order.operations().isEmpty()) {
            return FastEstimate.failed("工艺路线没有可排工序，无法估算交期");
        }
        if (resources == null || resources.isEmpty()) {
            return FastEstimate.noResources();
        }
        return estimateInternal(order, order.qty(), resources, busyByResource, readyAt, horizonEnd,
                requestedDate, allowSplitBatch, zone, true);
    }

    private static FastEstimate estimateInternal(
            SchedulingOrder order,
            BigDecimal qty,
            List<SchedulingResource> resources,
            Map<UUID, List<AvailabilityWindow>> busyByResource,
            OffsetDateTime readyAt,
            OffsetDateTime horizonEnd,
            LocalDate requestedDate,
            boolean allowSplitBatch,
            ZoneId zone,
            boolean allowSplitSuggestion) {
        Map<UUID, SchedulingResource> resourceById = new HashMap<>();
        for (SchedulingResource resource : resources) {
            resourceById.put(resource.resourceId(), resource);
        }
        List<SchedulingOperation> operations = new ArrayList<>(order.operations());
        operations.sort(Comparator.comparingInt(SchedulingOperation::operationIndex));

        List<Placement> placements = new ArrayList<>();
        OffsetDateTime ready = readyAt;
        for (SchedulingOperation operation : operations) {
            SchedulingResource resource = resolveNominalResource(operation, resources, resourceById);
            if (resource == null) {
                return FastEstimate.noResources();
            }
            long durationSeconds = durationSeconds(resource, operation, qty);
            List<AvailabilityWindow> busy = busyByResource.getOrDefault(resource.resourceId(), List.of());
            Fit fit = findFit(resource.availabilityWindows(), busy, ready, durationSeconds, horizonEnd);
            if (fit == null) {
                return FastEstimate.notFit(placements, resource.code(),
                        operation.operationCode(), operation.operationIndex());
            }
            placements.add(new Placement(operation, resource, ready, fit.start(), fit.end(),
                    durationSeconds, fit.busyWaitSeconds(), fit.calendarWaitSeconds()));
            ready = fit.end();
        }
        if (placements.isEmpty()) {
            return FastEstimate.failed("工艺路线没有可排工序，无法估算交期");
        }
        OffsetDateTime planFrom = placements.get(0).start();
        OffsetDateTime planTo = placements.get(placements.size() - 1).end();
        LocalDate deliveryDate = planTo.atZoneSameInstant(zone).toLocalDate();
        List<AtpBottleneck> bottlenecks = buildBottlenecks(placements);
        List<AtpSuggestion> suggestions = buildSuggestions(order, qty, resources, busyByResource, horizonEnd,
                placements, planFrom, planTo, deliveryDate, requestedDate, allowSplitBatch, zone,
                allowSplitSuggestion);
        return new FastEstimate(true, true, deliveryDate, planFrom, planTo, List.copyOf(placements),
                bottlenecks, suggestions, distinctResourceCodes(placements),
                "FAST 构造启发式估算：最早可交付 " + deliveryDate);
    }

    private static SchedulingResource resolveNominalResource(
            SchedulingOperation operation,
            List<SchedulingResource> resources,
            Map<UUID, SchedulingResource> resourceById) {
        if (operation.primaryResourceId() != null) {
            return resourceById.get(operation.primaryResourceId());
        }
        return candidates(operation, resources).stream()
                .min(Comparator.comparing(SchedulingResource::code))
                .orElse(null);
    }

    private static List<SchedulingResource> candidates(
            SchedulingOperation operation, List<SchedulingResource> resources) {
        if (operation.primaryResourceGroupId() == null) {
            return new ArrayList<>(resources);
        }
        List<SchedulingResource> grouped = new ArrayList<>();
        for (SchedulingResource resource : resources) {
            if (operation.primaryResourceGroupId().equals(resource.groupId())) {
                grouped.add(resource);
            }
        }
        return grouped;
    }

    private static long durationSeconds(
            SchedulingResource resource, SchedulingOperation operation, BigDecimal qty) {
        long run = OperationRunDuration.seconds(resource, operation, qty);
        long setup = 0L;
        if (operation.setupSeconds() != null && operation.setupSeconds().signum() > 0) {
            setup = operation.setupSeconds().setScale(0, RoundingMode.CEILING).longValueExact();
        }
        return Math.max(1L, run + setup);
    }

    private static Fit findFit(
            List<AvailabilityWindow> windows,
            List<AvailabilityWindow> busy,
            OffsetDateTime ready,
            long durationSeconds,
            OffsetDateTime horizonEnd) {
        if (!ready.isBefore(horizonEnd)) {
            return null;
        }
        List<AvailabilityWindow> effective = windows.isEmpty()
                ? List.of(new AvailabilityWindow(ready, horizonEnd))
                : windows;
        long busyWaitSeconds = 0L;
        for (AvailabilityWindow window : effective) {
            OffsetDateTime cursor = window.start().isBefore(ready) ? ready : window.start();
            if (!cursor.isBefore(window.end()) || !cursor.isBefore(horizonEnd)) {
                continue;
            }
            while (true) {
                OffsetDateTime candidateEnd = cursor.plusSeconds(durationSeconds);
                if (!candidateEnd.isAfter(window.end()) && !candidateEnd.isAfter(horizonEnd)) {
                    OffsetDateTime conflictEnd = firstConflictEnd(busy, cursor, candidateEnd);
                    if (conflictEnd == null) {
                        long totalWaitSeconds = Math.max(0L, Duration.between(ready, cursor).getSeconds());
                        long calendarWait = Math.max(0L, totalWaitSeconds - busyWaitSeconds);
                        return new Fit(cursor, candidateEnd, busyWaitSeconds, calendarWait);
                    }
                    OffsetDateTime clamped = conflictEnd.isAfter(window.end()) ? window.end() : conflictEnd;
                    busyWaitSeconds += Math.max(0L, Duration.between(cursor, clamped).getSeconds());
                    cursor = clamped;
                } else {
                    break;
                }
                if (!cursor.isBefore(window.end())) {
                    break;
                }
            }
        }
        return null;
    }

    private static OffsetDateTime firstConflictEnd(
            List<AvailabilityWindow> busy, OffsetDateTime start, OffsetDateTime end) {
        OffsetDateTime conflictEnd = null;
        for (AvailabilityWindow interval : busy) {
            if (interval.start().isBefore(end) && interval.end().isAfter(start)
                    && (conflictEnd == null || interval.end().isAfter(conflictEnd))) {
                conflictEnd = interval.end();
            }
        }
        return conflictEnd;
    }

    private static List<AtpBottleneck> buildBottlenecks(List<Placement> placements) {
        List<AtpBottleneck> bottlenecks = new ArrayList<>();
        for (Placement placement : placements) {
            if (placement.busyWaitSeconds() >= MIN_MEANINGFUL_WAIT_SECONDS) {
                bottlenecks.add(new AtpBottleneck("RESOURCE_QUEUE", placement.resourceCode(),
                        placement.operation().operationCode(), placement.operation().operationIndex(),
                        toMinutes(placement.busyWaitSeconds()),
                        "资源 " + placement.resourceCode() + " 在可开工后有既有任务占用，排队等待 "
                                + toMinutes(placement.busyWaitSeconds()) + " 分钟"));
            }
            if (placement.calendarWaitSeconds() >= MIN_MEANINGFUL_WAIT_SECONDS) {
                bottlenecks.add(new AtpBottleneck("CALENDAR_GAP", placement.resourceCode(),
                        placement.operation().operationCode(), placement.operation().operationIndex(),
                        toMinutes(placement.calendarWaitSeconds()),
                        "资源 " + placement.resourceCode() + " 的日历空窗（非工作时段）导致等待 "
                                + toMinutes(placement.calendarWaitSeconds()) + " 分钟"));
            }
        }
        if (bottlenecks.isEmpty()) {
            Placement longest = placements.stream()
                    .max(Comparator.comparingLong(Placement::durationSeconds))
                    .orElse(placements.get(0));
            bottlenecks.add(new AtpBottleneck("OPERATION_DURATION", longest.resourceCode(),
                    longest.operation().operationCode(), longest.operation().operationIndex(), 0,
                    "无资源排队或日历空窗；决定交期的是工序 "
                            + longest.operation().operationCode() + " 的加工时长 "
                            + toMinutes(longest.durationSeconds()) + " 分钟"));
        }
        bottlenecks.sort(Comparator.comparingInt(AtpBottleneck::delayMinutes).reversed());
        return List.copyOf(bottlenecks.subList(0, Math.min(MAX_BOTTLENECKS, bottlenecks.size())));
    }

    private static List<AtpSuggestion> buildSuggestions(
            SchedulingOrder order,
            BigDecimal qty,
            List<SchedulingResource> resources,
            Map<UUID, List<AvailabilityWindow>> busyByResource,
            OffsetDateTime horizonEnd,
            List<Placement> placements,
            OffsetDateTime planFrom,
            OffsetDateTime planTo,
            LocalDate deliveryDate,
            LocalDate requestedDate,
            boolean allowSplitBatch,
            ZoneId zone,
            boolean allowSplitSuggestion) {
        List<AtpSuggestion> suggestions = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        addQueueSuggestions(placements, qty, resources, busyByResource, horizonEnd, zone, suggestions, keys);
        addCalendarSuggestions(placements, zone, suggestions, keys);
        if (requestedDate != null && deliveryDate.isAfter(requestedDate)) {
            long lateDays = ChronoUnit.DAYS.between(requestedDate, deliveryDate);
            addSuggestion(suggestions, keys, new AtpSuggestion("ADJUST_PROMISED_DATE", null, null, null, 0,
                    "最早可交付 " + deliveryDate + "，比请求交期 " + requestedDate + " 晚 " + lateDays
                            + " 天；建议将承诺交期调整为 " + deliveryDate + " 或与客户协商顺延"));
            if (allowSplitSuggestion && allowSplitBatch && qty.compareTo(BigDecimal.valueOf(2)) >= 0) {
                BigDecimal halfQty = qty.divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
                if (halfQty.signum() > 0) {
                    FastEstimate half = estimateInternal(order, halfQty, resources, busyByResource,
                            planFrom, horizonEnd, requestedDate, false, zone, false);
                    if (half.completed() && half.earliestDeliveryDate() != null
                            && half.earliestDeliveryDate().isBefore(deliveryDate)) {
                        addSuggestion(suggestions, keys, new AtpSuggestion("SPLIT_BATCH", null, null, null,
                                0, "按 50% 拆批（首批 " + halfQty.toPlainString() + "）预计首批 "
                                + half.earliestDeliveryDate() + " 可交付，剩余批次 " + deliveryDate
                                + "；需确认拆批后的换型/准备成本（FAST 未计换型矩阵）"));
                    }
                }
            }
        }
        if (suggestions.isEmpty()) {
            String resourcesText = distinctResourceCodes(placements).isEmpty()
                    ? "相关资源" : String.join("/", distinctResourceCodes(placements));
            addSuggestion(suggestions, keys, new AtpSuggestion("LOCK_CAPACITY", null, null, null, 0,
                    "按当前估算可在 " + deliveryDate + " 承诺；确认订单后需锁定 " + resourcesText + " 的 "
                            + format(planFrom, zone) + "–" + format(planTo, zone)
                            + " 时段（ATP 本身未占用产能）"));
        }
        return List.copyOf(suggestions.subList(0, Math.min(MAX_SUGGESTIONS, suggestions.size())));
    }

    private static void addQueueSuggestions(
            List<Placement> placements,
            BigDecimal qty,
            List<SchedulingResource> resources,
            Map<UUID, List<AvailabilityWindow>> busyByResource,
            OffsetDateTime horizonEnd,
            ZoneId zone,
            List<AtpSuggestion> suggestions,
            Set<String> keys) {
        placements.stream()
                .filter(p -> p.busyWaitSeconds() >= MIN_MEANINGFUL_WAIT_SECONDS)
                .sorted(Comparator.comparingLong(Placement::busyWaitSeconds).reversed())
                .limit(2)
                .forEach(placement -> {
                    Fit alternative = bestAlternative(placement, qty, resources, busyByResource, horizonEnd);
                    if (alternative != null) {
                        long gainSeconds = Duration.between(alternative.end(), placement.end()).getSeconds();
                        if (gainSeconds >= MIN_MEANINGFUL_WAIT_SECONDS) {
                            String altCode = alternative.resourceCode();
                            addSuggestion(suggestions, keys, new AtpSuggestion(
                                    "SHIFT_TO_ALTERNATE_RESOURCE", altCode,
                                    placement.operation().operationCode(),
                                    placement.operation().operationIndex(), toMinutes(gainSeconds),
                                    "将工序 " + placement.operation().operationCode() + " 改派到备选资源 "
                                            + altCode + "，最早可提前 " + toMinutes(gainSeconds)
                                            + " 分钟（前提：工艺允许换资源）"));
                            return;
                        }
                    }
                    addSuggestion(suggestions, keys, new AtpSuggestion("ADD_OVERTIME",
                            placement.resourceCode(), placement.operation().operationCode(),
                            placement.operation().operationIndex(), toMinutes(placement.busyWaitSeconds()),
                            "资源 " + placement.resourceCode() + " 在 " + format(placement.start(), zone)
                                    + " 前排队 " + toMinutes(placement.busyWaitSeconds())
                                    + " 分钟；在该日增开班次约 "
                                    + toMinutes(placement.busyWaitSeconds()) + " 分钟可吸收排队等待"));
                });
    }

    private static void addCalendarSuggestions(
            List<Placement> placements, ZoneId zone, List<AtpSuggestion> suggestions, Set<String> keys) {
        placements.stream()
                .filter(p -> p.calendarWaitSeconds() >= MIN_MEANINGFUL_WAIT_SECONDS)
                .sorted(Comparator.comparingLong(Placement::calendarWaitSeconds).reversed())
                .limit(2)
                .forEach(placement -> addSuggestion(suggestions, keys, new AtpSuggestion("ADD_SHIFT",
                        placement.resourceCode(), placement.operation().operationCode(),
                        placement.operation().operationIndex(), toMinutes(placement.calendarWaitSeconds()),
                        "资源 " + placement.resourceCode() + " 的日历空窗合计 "
                                + toMinutes(placement.calendarWaitSeconds()) + " 分钟；在 "
                                + format(placement.start(), zone) + " 前增开班次可提前约 "
                                + toMinutes(placement.calendarWaitSeconds()) + " 分钟")));
    }

    private static Fit bestAlternative(
            Placement placement,
            BigDecimal qty,
            List<SchedulingResource> resources,
            Map<UUID, List<AvailabilityWindow>> busyByResource,
            OffsetDateTime horizonEnd) {
        List<SchedulingResource> alternatives = candidates(placement.operation(), resources).stream()
                .filter(r -> !r.resourceId().equals(placement.resource().resourceId()))
                .sorted(Comparator.comparing(SchedulingResource::code))
                .limit(MAX_ALTERNATIVE_RESOURCES)
                .toList();
        Fit best = null;
        for (SchedulingResource alternative : alternatives) {
            List<AvailabilityWindow> busy = busyByResource.getOrDefault(alternative.resourceId(), List.of());
            Fit fit = findFit(alternative.availabilityWindows(), busy, placement.ready(),
                    durationSeconds(alternative, placement.operation(), qty), horizonEnd);
            if (fit != null && fit.end().isBefore(placement.end())
                    && (best == null || fit.end().isBefore(best.end()))) {
                best = new Fit(fit.start(), fit.end(), 0L, 0L, alternative.code());
            }
        }
        return best;
    }

    private static void addSuggestion(
            List<AtpSuggestion> suggestions, Set<String> keys, AtpSuggestion suggestion) {
        String key = suggestion.action() + "|" + suggestion.resourceCode() + "|"
                + suggestion.operationIndex();
        if (keys.add(key)) {
            suggestions.add(suggestion);
        }
    }

    private static List<String> distinctResourceCodes(List<Placement> placements) {
        return placements.stream().map(Placement::resourceCode).distinct().toList();
    }

    private static int toMinutes(long seconds) {
        return (int) Math.max(0L, seconds / 60L);
    }

    private static String format(OffsetDateTime time, ZoneId zone) {
        if (time == null) {
            return "-";
        }
        return time.atZoneSameInstant(zone).format(DATE_TIME_FORMAT);
    }

    /**
     * FAST 估算输出。
     *
     * @param resourcesAvailable  是否存在可用主资源
     * @param completed           是否在 horizon 内完成全部工序
     * @param earliestDeliveryDate 最早交期
     * @param planFrom            假设排程起点
     * @param planTo              假设排程终点
     * @param placements          工序落位
     * @param bottlenecks         瓶颈
     * @param suggestions         建议
     * @param resourceCodes       涉及资源编码
     * @param message             结论说明
     */
    public record FastEstimate(
            boolean resourcesAvailable,
            boolean completed,
            LocalDate earliestDeliveryDate,
            OffsetDateTime planFrom,
            OffsetDateTime planTo,
            List<Placement> placements,
            List<AtpBottleneck> bottlenecks,
            List<AtpSuggestion> suggestions,
            List<String> resourceCodes,
            String message) {

        static FastEstimate noResources() {
            return new FastEstimate(false, false, null, null, null, List.of(),
                    List.of(new AtpBottleneck("NO_RESOURCE", null, null, null, 0,
                            "没有可用的 ACTIVE 资源（或工艺主资源缺失），无法估算交期")),
                    List.of(new AtpSuggestion("MAINTAIN_RESOURCE", null, null, null, 0,
                            "请维护至少 1 个 ACTIVE 资源并绑定日历（7×24 可留空）后重试")),
                    List.of(), "无可用资源，无法给出交期");
        }

        static FastEstimate notFit(
                List<Placement> placements, String resourceCode, String operationCode, int operationIndex) {
            return new FastEstimate(true, false, null,
                    placements.isEmpty() ? null : placements.get(0).start(),
                    placements.isEmpty() ? null : placements.get(placements.size() - 1).end(),
                    List.copyOf(placements),
                    List.of(new AtpBottleneck("INFEASIBLE_HORIZON", resourceCode, operationCode,
                            operationIndex, 0, "在估算 horizon 内找不到可容纳该工序的连续空档")),
                    List.of(new AtpSuggestion("EXTEND_TIMEOUT", resourceCode, operationCode, operationIndex,
                            0, "请检查资源日历有效期，或增加班次/扩大估算窗口后重试")),
                    placements.stream().map(Placement::resourceCode).distinct().toList(),
                    "估算窗口内无可行日期，未给出交期");
        }

        static FastEstimate failed(String message) {
            return new FastEstimate(true, false, null, null, null, List.of(), List.of(), List.of(),
                    List.of(), message);
        }
    }

    /**
     * 工序落位。
     *
     * @param operation           工序
     * @param resource            资源
     * @param ready               前序完工、可开工时间
     * @param start               实际开始
     * @param end                 结束
     * @param durationSeconds     工序总秒数（含 setup）
     * @param busyWaitSeconds     资源排队等待
     * @param calendarWaitSeconds 日历空窗等待
     */
    public record Placement(
            SchedulingOperation operation,
            SchedulingResource resource,
            OffsetDateTime ready,
            OffsetDateTime start,
            OffsetDateTime end,
            long durationSeconds,
            long busyWaitSeconds,
            long calendarWaitSeconds) {

        String resourceCode() {
            return resource.code();
        }
    }

    private record Fit(
            OffsetDateTime start,
            OffsetDateTime end,
            long busyWaitSeconds,
            long calendarWaitSeconds,
            String resourceCode) {

        Fit(OffsetDateTime start, OffsetDateTime end, long busyWaitSeconds, long calendarWaitSeconds) {
            this(start, end, busyWaitSeconds, calendarWaitSeconds, null);
        }
    }
}
