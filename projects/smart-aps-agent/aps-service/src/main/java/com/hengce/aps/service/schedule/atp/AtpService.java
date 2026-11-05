package com.hengce.aps.service.schedule.atp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategory;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategoryMapper;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.GanttTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpOrderSpec;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpSnapshot;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.api.SolverEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ATP 交期预估服务：只读，绝不写 {@code task}/{@code task_adjustment}/{@code plan_version}/{@code orders}，
 * 也不创建 {@code solve_task}。
 *
 * <p>FAST 走构造启发式（{@link AtpFastEstimator}）；ACCURATE 走 Timefold 限时求解
 * （{@link AtpAccurateCalculator} 翻译结论）。两档都读取 BASELINE 场景的既有任务，
 * 仅做“假设排程”，产能锁定只以 {@link CapacityLockSuggestion} 形式给出建议。</p>
 */
@Service
public class AtpService {

    private static final Set<String> ESTIMABLE_ORDER_STATUSES =
            Set.of("CONFIRMED", "SCHEDULED", "RELEASED", "IN_PROGRESS");

    private static final String ORDER_NOT_FOUND_MESSAGE = "订单不存在: ";

    private final ScheduleRepository scheduleRepository;
    private final ScheduleProblemAssembler assembler;
    private final MaterialMapper materialMapper;
    private final MaterialCategoryMapper materialCategoryMapper;
    /**
     * ACCURATE 使用的软约束权重：ATP 只关心"最早可交付"，因此把 MIN_MAKESPAN 从默认 10 提高到 20，
     * 其余规则保持默认比例（8 条合计必须为 100，由 Timefold 适配器校验）。
     */
    private static final Map<String, Integer> ATP_SOFT_WEIGHTS = Map.of(
            "MIN_TARDINESS", 20,
            "EDD", 10,
            "SPT", 10,
            "SAME_PRODUCT_CONTINUOUS", 10,
            "MIN_SETUP_TIME", 10,
            "BALANCE_LOAD", 10,
            "PRIORITY_RESPECT", 10,
            "MIN_MAKESPAN", 20);

    private final ObjectProvider<SolverAdapter> solverAdapters;
    private final ZoneId businessZone;
    private final int defaultTimeoutSeconds;
    private final int fastHorizonHours;
    private final int accurateHorizonHours;

    /**
     * 构造 ATP 服务。
     *
     * @param scheduleRepository 排产读取层
     * @param assembler 求解题面装配器
     * @param materialMapper 物料 Mapper
     * @param materialCategoryMapper 物料分类 Mapper
     * @param solverAdapters 求解器适配器（可为空，ACCURATE 时校验）
     * @param businessTimeZone 业务时区
     * @param accurateTimeoutSeconds ACCURATE 默认限时秒数
     * @param fastHorizonHours FAST 估算窗口小时数
     * @param accurateHorizonHours ACCURATE 题面资源窗口小时数
     */
    public AtpService(
            ScheduleRepository scheduleRepository,
            ScheduleProblemAssembler assembler,
            MaterialMapper materialMapper,
            MaterialCategoryMapper materialCategoryMapper,
            ObjectProvider<SolverAdapter> solverAdapters,
            @Value("${aps.business.time-zone:Asia/Shanghai}") String businessTimeZone,
            @Value("${aps.atp.accurate-timeout-seconds:10}") int accurateTimeoutSeconds,
            @Value("${aps.atp.fast-horizon-hours:1080}") int fastHorizonHours,
            @Value("${aps.atp.accurate-horizon-hours:172}") int accurateHorizonHours) {
        this.scheduleRepository = scheduleRepository;
        this.assembler = assembler;
        this.materialMapper = materialMapper;
        this.materialCategoryMapper = materialCategoryMapper;
        this.solverAdapters = solverAdapters;
        this.businessZone = ZoneId.of(businessTimeZone.trim());
        this.defaultTimeoutSeconds = accurateTimeoutSeconds;
        this.fastHorizonHours = fastHorizonHours;
        this.accurateHorizonHours = accurateHorizonHours;
    }

    /**
     * 执行 ATP 预估。
     *
     * @param query 入参
     * @return 预估结果
     */
    public AtpResult estimate(AtpQuery query) {
        AtpRequestValidator.NormalizedAtpQuery normalized =
                AtpRequestValidator.validate(query, defaultTimeoutSeconds);
        UUID tenantId = TenantContext.getTenantId();
        OrderSourceRow existingOrder = null;
        if (normalized.orderNo() != null) {
            List<OrderSourceRow> rows =
                    scheduleRepository.findOrders(tenantId, List.of(normalized.orderNo()), false);
            if (rows.isEmpty()) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        ORDER_NOT_FOUND_MESSAGE + normalized.orderNo());
            }
            existingOrder = rows.get(0);
            requireEstimableStatus(existingOrder);
            if (existingOrder.materialCode() != null
                    && !existingOrder.materialCode().equals(normalized.materialCode())) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单 " + existingOrder.orderNo() + " 的产品 " + existingOrder.materialCode()
                                + " 与请求 materialCode " + normalized.materialCode() + " 不一致");
            }
        }
        Material material = findMaterial(normalized.materialCode());
        if (material == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "物料不存在: " + normalized.materialCode());
        }
        String productFamily = findProductFamily(material);
        String orderNo = existingOrder == null
                ? "ATP-" + UUID.randomUUID().toString().substring(0, 8)
                : existingOrder.orderNo();
        UUID orderId = existingOrder == null ? UUID.randomUUID() : existingOrder.orderId();
        String priority = existingOrder == null || existingOrder.priority() == null
                ? "NORMAL" : existingOrder.priority();
        AtpOrderSpec spec = new AtpOrderSpec(orderId, orderNo, normalized.materialCode(),
                productFamily, normalized.qty(), normalized.requestedDate(), priority, null);
        if (normalized.mode() == AtpMode.FAST) {
            return estimateFast(normalized, spec);
        }
        return estimateAccurate(normalized, spec);
    }

    private void requireEstimableStatus(OrderSourceRow order) {
        if (!ESTIMABLE_ORDER_STATUSES.contains(order.status())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "订单 " + order.orderNo() + " 当前状态 " + order.status()
                            + " 不允许交期预估（允许 "
                            + String.join("/", ESTIMABLE_ORDER_STATUSES) + "）");
        }
    }

    private Material findMaterial(String materialCode) {
        LambdaQueryWrapper<Material> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Material::getCode, materialCode).last("LIMIT 1");
        List<Material> materials = materialMapper.selectList(wrapper);
        return materials.isEmpty() ? null : materials.get(0);
    }

    private String findProductFamily(Material material) {
        if (material.getCategoryId() == null) {
            return null;
        }
        MaterialCategory category = materialCategoryMapper.selectById(material.getCategoryId());
        return category == null ? null : category.getCode();
    }

    private AtpResult estimateFast(
            AtpRequestValidator.NormalizedAtpQuery query, AtpOrderSpec spec) {
        long startedAt = System.nanoTime();
        OffsetDateTime readyAt = OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        AtpSnapshot snapshot = assembler.assembleAtp(spec, fastHorizonHours, false);
        if (snapshot.atpOrder() == null) {
            long elapsedMs = elapsedMillis(startedAt);
            return noResourceResult(query, spec, elapsedMs,
                    new AtpSolveInfo("heuristic", null, elapsedMs, false, "COMPLETED",
                            null, null, 0, 0, "无可用资源"));
        }
        Map<UUID, List<AvailabilityWindow>> busy =
                loadBusyIntervals(snapshot, query.orderNo());
        AtpFastEstimator.FastEstimate estimate = AtpFastEstimator.estimate(
                snapshot.atpOrder(), snapshot.problem().resources(), busy, readyAt,
                snapshot.horizonEnd(), query.requestedDate(), query.allowSplitBatch(), businessZone);
        long elapsedMs = elapsedMillis(startedAt);
        return toFastResult(query, spec, estimate, elapsedMs);
    }

    private AtpResult estimateAccurate(
            AtpRequestValidator.NormalizedAtpQuery query, AtpOrderSpec spec) {
        SolverAdapter adapter = selectTimefoldAdapter();
        long startedAt = System.nanoTime();
        OffsetDateTime readyAt = OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        AtpSnapshot snapshot = assembler.assembleAtp(spec, accurateHorizonHours, true);
        if (snapshot.atpOrder() == null) {
            long elapsedMs = elapsedMillis(startedAt);
            return noResourceResult(query, spec, elapsedMs,
                    new AtpSolveInfo("timefold", query.timeoutSeconds(), elapsedMs, false, "INFEASIBLE",
                            null, null, 0, 0, "无可用资源"));
        }
        SolveConfig config = new SolveConfig("timefold", query.timeoutSeconds(), 0,
                "LATE_ACCEPTANCE", 0L, 0L, query.timeoutSeconds(), "FORWARD", true,
                Map.of("softWeights", ATP_SOFT_WEIGHTS));
        ScheduleSolution solution;
        try {
            solution = adapter.solve(snapshot.problem(), config);
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.SOLVER_NOT_AVAILABLE,
                    "ATP 限时求解失败: " + ex.getMessage());
        }
        long elapsedMs = elapsedMillis(startedAt);
        AtpAccurateCalculator.Outcome outcome = AtpAccurateCalculator.evaluate(
                solution, snapshot.atpOrder().orderId(), snapshot.atpOrder().operations().size(),
                query.requestedDate(), query.timeoutSeconds(), elapsedMs, businessZone);
        if (outcome.status() == AtpStatus.FAILED) {
            throw new BizException(ErrorCode.SOLVER_NOT_AVAILABLE, outcome.message());
        }
        AtpSolveInfo solveInfo = new AtpSolveInfo("timefold", query.timeoutSeconds(), elapsedMs,
                outcome.timedOut(), outcome.solverStatus(), outcome.hardScore(), outcome.softScore(),
                outcome.assignedCount(), outcome.unassignedCount(), outcome.message());
        Boolean feasible = outcome.earliestDeliveryDate() == null
                ? (outcome.status() == AtpStatus.TIMEOUT ? null : Boolean.FALSE)
                : Boolean.valueOf(!outcome.earliestDeliveryDate().isAfter(query.requestedDate()));
        Integer leadTimeDays = outcome.earliestDeliveryDate() == null ? null
                : leadDays(readyAt, outcome.earliestDeliveryDate());
        String bottleneckResourceCode = firstResourceCode(outcome.bottlenecks());
        CapacityLockSuggestion lock = new CapacityLockSuggestion(
                true,
                outcome.earliestDeliveryDate() != null,
                outcome.earliestDeliveryDate() == null
                        ? "无确定交期，ATP 不占用也不建议锁定产能"
                        : "ATP 为只读估算，未占用产能；如需以该交期承诺，应在订单确认后锁定下列资源时段",
                outcome.earliestDeliveryDate() == null ? List.of() : distinctRoutedResources(solution, snapshot),
                outcome.earliestDeliveryDate() == null ? null : outcome.planFrom(),
                outcome.earliestDeliveryDate() == null ? null : outcome.planTo());
        return new AtpResult(query.materialCode(), query.qty(), query.requestedDate(),
                spec.orderNo(), AtpMode.ACCURATE, outcome.accuracy(), outcome.status(), feasible,
                outcome.earliestDeliveryDate(), outcome.earliestDeliveryDate(), leadTimeDays,
                bottleneckResourceCode, outcome.bottlenecks(), outcome.suggestions(), lock, solveInfo,
                accurateAssumptions(), false, outcome.message());
    }

    private AtpResult toFastResult(
            AtpRequestValidator.NormalizedAtpQuery query, AtpOrderSpec spec,
            AtpFastEstimator.FastEstimate estimate, long elapsedMs) {
        LocalDate date = estimate.earliestDeliveryDate();
        Boolean feasible = date == null ? false : !date.isAfter(query.requestedDate());
        AtpStatus status = date == null ? AtpStatus.INFEASIBLE : AtpStatus.OK;
        Integer leadTimeDays = date == null ? null
                : leadDays(OffsetDateTime.now(ZoneOffset.UTC), date);
        String bottleneckResourceCode = firstResourceCode(estimate.bottlenecks());
        CapacityLockSuggestion lock = new CapacityLockSuggestion(
                true,
                date != null,
                date == null
                        ? "无确定交期，ATP 不占用也不建议锁定产能"
                        : "ATP 为只读估算，未占用产能；如需以该交期承诺，应在订单确认后锁定下列资源时段",
                date == null ? List.of() : estimate.resourceCodes(),
                date == null ? null : estimate.planFrom(),
                date == null ? null : estimate.planTo());
        AtpSolveInfo solveInfo = new AtpSolveInfo("heuristic", null, elapsedMs, false,
                date == null ? "INFEASIBLE" : "COMPLETED",
                null, null, estimate.placements().size(),
                date == null ? 0 : 0, estimate.message());
        return new AtpResult(query.materialCode(), query.qty(), query.requestedDate(), spec.orderNo(),
                AtpMode.FAST, AtpAccuracy.ESTIMATE, status, feasible, date, date, leadTimeDays,
                bottleneckResourceCode, estimate.bottlenecks(), estimate.suggestions(), lock, solveInfo,
                fastAssumptions(), false, estimate.message());
    }

    private AtpResult noResourceResult(
            AtpRequestValidator.NormalizedAtpQuery query, AtpOrderSpec spec, long elapsedMs,
            AtpSolveInfo solveInfo) {
        List<AtpBottleneck> bottlenecks = List.of(new AtpBottleneck("NO_RESOURCE", null, null, null, 0,
                "没有可用资源或工艺主资源缺失，无法给出交期"));
        List<AtpSuggestion> suggestions = List.of(new AtpSuggestion("MAINTAIN_RESOURCE", null, null, null, 0,
                "请维护至少 1 个 ACTIVE 资源并绑定日历（7×24 可留空）后重试"));
        CapacityLockSuggestion lock = new CapacityLockSuggestion(true, false,
                "无可用资源，ATP 不占用也不建议锁定产能", List.of(), null, null);
        return new AtpResult(query.materialCode(), query.qty(), query.requestedDate(), spec.orderNo(),
                query.mode(), query.mode() == AtpMode.FAST ? AtpAccuracy.ESTIMATE : AtpAccuracy.UNVERIFIED,
                AtpStatus.INFEASIBLE, false, null, null, null, null, bottlenecks, suggestions, lock,
                solveInfo, query.mode() == AtpMode.FAST ? fastAssumptions() : accurateAssumptions(),
                false, "无可用资源，无法给出交期");
    }

    private Map<UUID, List<AvailabilityWindow>> loadBusyIntervals(AtpSnapshot snapshot, String excludedOrderNo) {
        Map<String, UUID> resourceIdByCode = new HashMap<>();
        for (SchedulingResource resource : snapshot.problem().resources()) {
            resourceIdByCode.put(resource.code(), resource.resourceId());
        }
        Map<UUID, List<AvailabilityWindow>> busy = new HashMap<>();
        for (GanttTaskRow row : scheduleRepository.findGanttTasks(snapshot.tenantId(),
                ScheduleProblem.DEFAULT_SANDBOX_ID, null, snapshot.horizonStart(), snapshot.horizonEnd())) {
            if (excludedOrderNo != null && excludedOrderNo.equals(row.orderNo())) {
                continue;
            }
            UUID resourceId = resourceIdByCode.get(row.resourceCode());
            if (resourceId == null) {
                continue;
            }
            OffsetDateTime start = row.setupStartTime() == null ? row.startTime() : row.setupStartTime();
            OffsetDateTime end = row.endTime();
            if (start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            busy.computeIfAbsent(resourceId, key -> new ArrayList<>())
                    .add(new AvailabilityWindow(start, end));
        }
        busy.values().forEach(list -> list.sort(Comparator.comparing(AvailabilityWindow::start)));
        return busy;
    }

    private SolverAdapter selectTimefoldAdapter() {
        List<SolverAdapter> adapters = solverAdapters.orderedStream().toList();
        if (adapters.isEmpty()) {
            throw new BizException(ErrorCode.SOLVER_NOT_AVAILABLE, "ACCURATE 需要 Timefold 求解器，但当前未注册");
        }
        return adapters.stream()
                .filter(adapter -> adapter.engine() == SolverEngine.TIMEFOLD)
                .findFirst()
                .orElse(adapters.get(0));
    }

    private List<String> distinctRoutedResources(ScheduleSolution solution, AtpSnapshot snapshot) {
        Set<String> codes = new HashSet<>();
        UUID atpOrderId = snapshot.atpOrder().orderId();
        for (var assignment : solution.assignments()) {
            if (atpOrderId.equals(assignment.orderId()) && assignment.resourceCode() != null) {
                codes.add(assignment.resourceCode());
            }
        }
        return codes.stream().sorted().toList();
    }

    private static Integer leadDays(OffsetDateTime readyAt, LocalDate deliveryDate) {
        long days = ChronoUnit.DAYS.between(readyAt.toLocalDate(), deliveryDate);
        return (int) Math.max(0L, days);
    }

    private static String firstResourceCode(List<AtpBottleneck> bottlenecks) {
        return bottlenecks.stream().map(AtpBottleneck::resourceCode)
                .filter(code -> code != null && !code.isBlank()).findFirst().orElse(null);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static List<String> fastAssumptions() {
        return List.of(
                "FAST 为构造启发式估算，未运行 Timefold，准确性口径固定为 ESTIMATE。",
                "工序按 operationIndex 串行推进（FS/0），忽略 SS/FF 重叠，存在并行依赖时结果可能偏保守。",
                "工序时长按 setup + run 计算；资源取工序主资源，主资源为空时取资源组/全部资源中编码最小者。",
                "既有负荷只读取 BASELINE 场景 task（含冻结与已排任务），不含在制报工。",
                "日历空列表按 7×24 处理；业务时区为 Asia/Shanghai。",
                "本接口只读：不写 task/task_adjustment/plan_version/orders，不创建 solve_task。");
    }

    private static List<String> accurateAssumptions() {
        return List.of(
                "ACCURATE 使用 Timefold 限时求解，题面与主排产一致：CONFIRMED 订单 + FROZEN 任务，另追加假设订单参与重排。",
                "求解 horizon 为 7 天；超出窗口的需求会表现为未分配/不可行，此时不给出交期。",
                "只有拿到完整可行解才标注 VERIFIED；TIMEOUT/INFEASIBLE 一律 UNVERIFIED 且不给出日期。",
                "求解目标按 8 条软约束权重执行（MIN_MAKESPAN 加权到 20，其余保持默认比例，合计 100），"
                        + "因此日期是限时求解最佳解中的最早完工时间，仍不是最优性证明。",
                "本接口只读：不写 task/task_adjustment/plan_version/orders，不创建 solve_task。");
    }
}
