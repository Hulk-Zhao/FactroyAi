package com.hengce.aps.service.agent;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository.CompareTaskRow;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository.SandboxRow;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.service.sandbox.SandboxCompareView;
import com.hengce.aps.service.sandbox.SandboxComparisonCalculator;
import com.hengce.aps.service.sandbox.SandboxService;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 对话式 what-if 服务：在指定 WHAT_IF 场景内做一次真实求解，再用既有沙箱 compare
 * 返回“方案对比”。
 *
 * <p>不复制沙箱/求解/对比逻辑：求解走 {@link SolveTaskLifecycleService#submitSynchronous}，
 * 对比走 {@link SandboxService#compare}，瓶颈变化复用 {@link SandboxComparisonCalculator}
 * 的同一利用率口径。</p>
 *
 * <p>参数边界：只接受下面 {@link #SUPPORTED_PARAMETERS} 中的求解参数与 {@code orderNos}；
 * 其余键返回 42200 并逐一点名。BASELINE/ARCHIVED 场景一律拒绝。</p>
 */
@Service
public class AgentWhatIfService {

    private static final String TYPE_WHAT_IF = "WHAT_IF";

    /**
     * 受支持的求解参数键。
     */
    public static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "orderNos", "strategyId", "engine", "timeoutSeconds", "moveThreadCount", "acceptor",
            "randomSeed", "maxSteps", "unimprovedSecondsSpentLimit", "direction", "useFrozenAssignments");

    private static final Set<String> DIRECTIONS = Set.of("FORWARD", "BACKWARD", "BOTTLENECK");

    /**
     * 产品定稿的“推演失败”降级文案，不得自行改写。
     */
    private static final String SOLVE_FAILED_NARRATIVE =
            "推演失败：求解引擎不可用。假设未产生结论，正式计划未受影响。";

    private static final int RESOURCE_DELTA_LIMIT = 20;

    /**
     * 基线可比性警告：基线场景无任务，差值不可解释。
     */
    public static final String WARNING_BASELINE_EMPTY = "BASELINE_EMPTY";

    /**
     * 基线可比性警告：基线有任务但没有可用排程时间/资源，利用率与交期差不可解释。
     */
    public static final String WARNING_BASELINE_NOT_SCHEDULED = "BASELINE_NOT_SCHEDULED";

    /**
     * 基线可比性警告：基线任务数不足对比侧三分之一，差值主要由任务量差异驱动。
     */
    public static final String WARNING_BASELINE_SPARSE = "BASELINE_SPARSE";

    private static final int SPARSE_RATIO = 3;

    private final SolveTaskLifecycleService lifecycleService;

    private final SandboxService sandboxService;

    private final SandboxRepository sandboxRepository;

    /**
     * 构造服务。
     *
     * @param lifecycleService 求解任务生命周期服务
     * @param sandboxService 沙箱服务
     * @param sandboxRepository 场景持久层
     */
    public AgentWhatIfService(SolveTaskLifecycleService lifecycleService, SandboxService sandboxService,
                              SandboxRepository sandboxRepository) {
        this.lifecycleService = lifecycleService;
        this.sandboxService = sandboxService;
        this.sandboxRepository = sandboxRepository;
    }

    /**
     * 执行一次假设运行并返回方案对比。
     *
     * @param request 请求
     * @param idempotencyKey 求解幂等键，可空
     * @return what-if 结果
     */
    public AgentWhatIfView run(AgentWhatIfRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (request.question() == null || request.question().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "question 不能为空");
        }
        if (request.question().length() > 2000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "question 最长 2000 字符");
        }
        if (request.sandboxId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sandboxId 不能为空");
        }
        UUID tenantId = TenantContext.getTenantId();
        SandboxRow sandbox = sandboxRepository.find(tenantId, request.sandboxId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + request.sandboxId()));
        if (!TYPE_WHAT_IF.equals(sandbox.type())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "what-if 仅支持 WHAT_IF 场景，当前场景类型为 " + sandbox.type() + "，拒绝修改主计划");
        }
        SandboxRow baseline = sandboxRepository.findBaseline(tenantId)
                .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT,
                        "租户缺少 BASELINE 场景，无法生成方案对比"));
        List<CompareTaskRow> baselineRows = sandboxRepository.listCompareTasks(tenantId, baseline.id());

        Map<String, Object> parameters = validateParameters(request.parameters());
        List<AgentWhatIfView.AppliedChange> appliedChanges = new ArrayList<>();
        parameters.forEach((name, value) ->
                appliedChanges.add(new AgentWhatIfView.AppliedChange(name, value)));

        List<String> orderNos = orderNos(parameters);
        UUID strategyId = strategyId(parameters);
        String engine = engine(parameters);
        SolveRequest solveRequest = new SolveRequest(null, sandbox.id(), strategyId, engine, orderNos,
                parameters);

        SolveTaskDetail detail = lifecycleService.submitSynchronous(solveRequest, idempotencyKey, true);
        if (detail.sandboxId() != null && !detail.sandboxId().equals(sandbox.id())) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key 已用于其他场景的求解任务: " + detail.taskId());
        }
        List<CompareTaskRow> afterRows = sandboxRepository.listCompareTasks(tenantId, sandbox.id());
        if (!"SOLVED".equals(detail.status())) {
            String reason = detail.errorCode() == null ? detail.status() : detail.errorCode();
            return new AgentWhatIfView(sandbox.id(), baseline.id(), baselineRows.size(), afterRows.size(),
                    baselineRows.isEmpty(), List.of(), request.question(), appliedChanges,
                    detail.taskId(), detail.status(), detail.degraded(), detail.scoreHard(), detail.scoreSoft(),
                    detail.metrics(), false, "SOLVE_" + reason, null, List.of(), false, null,
                    SOLVE_FAILED_NARRATIVE, "EVIDENCE");
        }

        SandboxCompareView comparison = sandboxService.compare(sandbox.id(), baseline.id());
        AgentWhatIfView.BottleneckChange bottleneckChange = bottleneckChange(baselineRows, afterRows);
        List<String> comparisonWarnings = comparisonWarnings(baselineRows, afterRows);

        Set<String> impactedOrders = new LinkedHashSet<>();
        for (SandboxCompareView.ChangedTaskView changed : comparison.changedTasks()) {
            if (changed.orderNo() != null) {
                impactedOrders.add(changed.orderNo());
            }
        }
        List<String> impacted = impactedOrders.stream().sorted().toList();
        boolean impactedComplete = !comparison.truncated();
        String answer = buildAnswer(comparison, basedOn(detail, appliedChanges), bottleneckChange,
                impacted.size(), comparison.truncated(), comparisonWarnings);

        return new AgentWhatIfView(sandbox.id(), baseline.id(), baselineRows.size(), afterRows.size(),
                baselineRows.isEmpty(), comparisonWarnings, request.question(), appliedChanges,
                detail.taskId(), detail.status(), detail.degraded(), detail.scoreHard(), detail.scoreSoft(),
                detail.metrics(), true, null, comparison, impacted, impactedComplete, bottleneckChange,
                answer, "EVIDENCE");
    }

    private Map<String, Object> validateParameters(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        List<String> unsupported = raw.keySet().stream()
                .filter(key -> !SUPPORTED_PARAMETERS.contains(key))
                .sorted()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "不支持的 what-if 参数: " + String.join(", ", unsupported)
                            + "；支持: " + String.join(", ", SUPPORTED_PARAMETERS.stream().sorted().toList()));
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        raw.forEach((key, value) -> validated.put(key, validateValue(key, value)));
        return validated;
    }

    private Object validateValue(String key, Object value) {
        return switch (key) {
            case "orderNos" -> normalizeOrderNos(value);
            case "strategyId" -> normalizeStrategyId(value);
            case "engine" -> normalizeEngine(value);
            case "timeoutSeconds" -> integerRange(key, value, 1, 3600);
            case "moveThreadCount" -> integerRange(key, value, 0, 64);
            case "maxSteps" -> longRange(key, value, 0, Long.MAX_VALUE);
            case "unimprovedSecondsSpentLimit" -> integerRange(key, value, 0, 3600);
            case "randomSeed" -> longRange(key, value, Long.MIN_VALUE, Long.MAX_VALUE);
            case "direction" -> normalizeDirection(value);
            case "acceptor" -> nonBlankString(key, value);
            case "useFrozenAssignments" -> booleanValue(key, value);
            default -> throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "不支持的 what-if 参数: " + key);
        };
    }

    private static List<String> normalizeOrderNos(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 orderNos 必须是字符串数组");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 orderNos 的每一项必须是非空字符串");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static UUID normalizeStrategyId(Object value) {
        String text = nonBlankString("strategyId", value);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 strategyId 必须是 UUID");
        }
    }

    private static String normalizeEngine(Object value) {
        String text = nonBlankString("engine", value);
        try {
            return SolverEngine.fromCode(text).getCode();
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 engine 仅支持 TIMEFOLD / ORTOOLS");
        }
    }

    private static String normalizeDirection(Object value) {
        String text = nonBlankString("direction", value).toUpperCase(Locale.ROOT);
        if (!DIRECTIONS.contains(text)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "参数 direction 仅支持 " + String.join(" / ", DIRECTIONS));
        }
        return text;
    }

    private static int integerRange(String key, Object value, int min, int max) {
        if (!(value instanceof Number number)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 " + key + " 必须是整数");
        }
        double raw = number.doubleValue();
        if (raw != Math.rint(raw) || raw < min || raw > max) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "参数 " + key + " 必须是 " + min + "–" + max + " 的整数");
        }
        return (int) raw;
    }

    private static long longRange(String key, Object value, long min, long max) {
        if (!(value instanceof Number number)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 " + key + " 必须是整数");
        }
        double raw = number.doubleValue();
        if (raw != Math.rint(raw) || raw < min || raw > max) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 " + key + " 超出允许范围");
        }
        return (long) raw;
    }

    private static String nonBlankString(String key, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 " + key + " 必须是非空字符串");
        }
        return text.trim();
    }

    private static boolean booleanValue(String key, Object value) {
        if (!(value instanceof Boolean flag)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "参数 " + key + " 必须是布尔值");
        }
        return flag;
    }

    private static List<String> orderNos(Map<String, Object> parameters) {
        @SuppressWarnings("unchecked")
        List<String> orderNos = (List<String>) parameters.get("orderNos");
        return orderNos == null ? List.of() : orderNos;
    }

    private static UUID strategyId(Map<String, Object> parameters) {
        Object value = parameters.get("strategyId");
        return value == null ? null : (UUID) value;
    }

    private static String engine(Map<String, Object> parameters) {
        Object value = parameters.get("engine");
        return value == null ? null : (String) value;
    }

    private static AgentWhatIfView.BottleneckChange bottleneckChange(List<CompareTaskRow> beforeRows,
                                                                    List<CompareTaskRow> afterRows) {
        Map<String, Double> before = SandboxComparisonCalculator.resourceUtilization(beforeRows);
        Map<String, Double> after = SandboxComparisonCalculator.resourceUtilization(afterRows);
        Set<String> resources = new LinkedHashSet<>(before.keySet());
        resources.addAll(after.keySet());
        List<AgentWhatIfView.ResourceUtilizationDelta> deltas = new ArrayList<>();
        for (String resource : resources) {
            double beforeValue = before.getOrDefault(resource, 0.0);
            double afterValue = after.getOrDefault(resource, 0.0);
            double delta = Math.round((afterValue - beforeValue) * 1000.0) / 1000.0;
            deltas.add(new AgentWhatIfView.ResourceUtilizationDelta(resource, beforeValue, afterValue, delta));
        }
        deltas.sort(Comparator.comparingDouble(
                (AgentWhatIfView.ResourceUtilizationDelta item) -> Math.abs(item.delta())).reversed()
                .thenComparing(AgentWhatIfView.ResourceUtilizationDelta::resourceCode));
        List<AgentWhatIfView.ResourceUtilizationDelta> limited =
                deltas.size() > RESOURCE_DELTA_LIMIT ? List.copyOf(deltas.subList(0, RESOURCE_DELTA_LIMIT))
                        : List.copyOf(deltas);

        AgentWhatIfView.BottleneckPoint beforePeak = peak(before);
        AgentWhatIfView.BottleneckPoint afterPeak = peak(after);
        boolean comparable = beforePeak != null && afterPeak != null;
        String notComparableReason = comparable ? null
                : beforePeak == null ? "BASELINE_NO_RESOURCE_UTILIZATION"
                : "AFTER_NO_RESOURCE_UTILIZATION";
        boolean changed = comparable
                && (!beforePeak.resourceCode().equals(afterPeak.resourceCode())
                || Math.abs(afterPeak.utilization() - beforePeak.utilization()) >= 0.01);
        return new AgentWhatIfView.BottleneckChange(beforePeak, afterPeak, comparable, notComparableReason,
                changed, "资源利用率（占用分钟/观察窗口），取利用率最高的资源", limited);
    }

    /**
     * 判定基线可比性：基线为空、基线没有可用排程时间、或任务量相差 3 倍以上时给出警告编码，
     * 避免把裸差值当成“排程变化”解读。
     *
     * @param baselineRows 基线任务行
     * @param afterRows 对比侧任务行
     * @return 警告编码，按固定顺序；无可比性问题时为空
     */
    public static List<String> comparisonWarnings(List<CompareTaskRow> baselineRows,
                                                  List<CompareTaskRow> afterRows) {
        List<String> warnings = new ArrayList<>();
        if (baselineRows.isEmpty()) {
            warnings.add(WARNING_BASELINE_EMPTY);
            return List.copyOf(warnings);
        }
        if (SandboxComparisonCalculator.resourceUtilization(baselineRows).isEmpty()) {
            warnings.add(WARNING_BASELINE_NOT_SCHEDULED);
        }
        if (afterRows.size() >= (long) baselineRows.size() * SPARSE_RATIO) {
            warnings.add(WARNING_BASELINE_SPARSE);
        }
        return List.copyOf(warnings);
    }

    private static AgentWhatIfView.BottleneckPoint peak(Map<String, Double> utilization) {
        return utilization.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new AgentWhatIfView.BottleneckPoint(entry.getKey(), entry.getValue()))
                .findFirst()
                .orElse(null);
    }

    private static String basedOn(SolveTaskDetail detail, List<AgentWhatIfView.AppliedChange> changes) {
        if (changes.isEmpty()) {
            return "未指定求解参数";
        }
        return "参数 " + changes.stream()
                .map(change -> change.parameter() + "=" + change.value())
                .toList();
    }

    private static String buildAnswer(SandboxCompareView comparison, String parameterSummary,
                                      AgentWhatIfView.BottleneckChange bottleneck, int impactedOrders,
                                      boolean truncated, List<String> comparisonWarnings) {
        String bottleneckText;
        if (bottleneck == null || !bottleneck.comparable()) {
            bottleneckText = "瓶颈资源不可比较（"
                    + (bottleneck != null && "AFTER_NO_RESOURCE_UTILIZATION".equals(
                            bottleneck.notComparableReason()) ? "对比侧无可用资源时间" : "基线无可用资源时间")
                    + "）";
        } else if (bottleneck.changed()) {
            bottleneckText = String.format("瓶颈资源 %s(%.3f) → %s(%.3f)",
                    bottleneck.before().resourceCode(), bottleneck.before().utilization(),
                    bottleneck.after().resourceCode(), bottleneck.after().utilization());
        } else {
            bottleneckText = String.format("瓶颈资源未变化（%s %.3f）",
                    bottleneck.before().resourceCode(), bottleneck.before().utilization());
        }
        String warningText = comparisonWarnings.isEmpty() ? ""
                : "；基线可比性警告：" + String.join("；", comparisonWarnings.stream()
                        .map(AgentWhatIfService::warningText).toList());
        return String.format(
                "在 %s 下重算，对比基线：新增 %d、移出 %d、变更 %d 条任务；"
                        + "交期超期差 %d 分钟，资源利用率均值差 %.3f，换型数差 %d；"
                        + "%s；受影响订单 %d 个%s%s。",
                parameterSummary, comparison.addedTaskCount(), comparison.removedTaskCount(),
                comparison.movedTaskCount(), comparison.dueDateDeltaMinutes(),
                comparison.utilizationDelta(), comparison.setupCountDelta(), bottleneckText,
                impactedOrders, truncated ? "（变更明细已截断，订单数为下限）" : "", warningText);
    }

    private static String warningText(String code) {
        return switch (code) {
            case WARNING_BASELINE_EMPTY -> "基线场景无任务，新增/移出计数与交期差不可解释";
            case WARNING_BASELINE_NOT_SCHEDULED -> "基线任务无可用排程时间，利用率差不可解释";
            case WARNING_BASELINE_SPARSE -> "基线任务数不足对比侧三分之一，差值主要由任务量差异驱动";
            default -> code;
        };
    }
}
