package com.hengce.aps.service.schedule.infeasibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository.SolveTaskRecord;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository.SolveTaskReplayRow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.SolveFinishEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 不可行归因分析器（B1）。
 *
 * <p>按 {@code solveTaskId} 重放题面 → 采集确定性证据 → 判定 verdict → 产出修复候选。
 * 不调用任何模型；{@code llmStatus} 恒为 {@code DISABLED}。</p>
 *
 * <p><b>verdict 纪律</b>：只有 {@code FAILED}/{@code TIMEOUT} 且存在结构性不可行证明时
 * 才返回 {@code NO_FEASIBLE_SOLUTION_FOUND}；“预算内没找到解”一律停在
 * {@code NO_EVIDENCE} / {@code PARTIAL_EVIDENCE}，不冒充不可行。</p>
 */
@Component
public class InfeasibilityAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(InfeasibilityAnalyzer.class);

    /**
     * JSON 解析器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 成功终态。
     */
    private static final String STATUS_SOLVED = "SOLVED";

    /**
     * 失败终态。
     */
    private static final String STATUS_FAILED = "FAILED";

    /**
     * 超时终态。
     */
    private static final String STATUS_TIMEOUT = "TIMEOUT";

    /**
     * 默认超时秒数。
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * 默认无改进秒数。
     */
    private static final int DEFAULT_UNIMPROVED_SECONDS = 10;

    /**
     * 默认接受器。
     */
    private static final String DEFAULT_ACCEPTOR = "LATE_ACCEPTANCE";

    /**
     * 默认排产方向。
     */
    private static final String DEFAULT_DIRECTION = "FORWARD";

    /**
     * 叙事里最多展开的证明条数。
     */
    private static final int NARRATIVE_PROOF_LIMIT = 3;

    private final ScheduleProblemAssembler assembler;

    private final SolveTaskRepository solveTaskRepository;

    private final InfeasibilityQueryRepository queryRepository;

    private final ConstraintJustificationRepository justificationRepository;

    /**
     * 构造分析器。
     *
     * @param assembler 题面装配器（复用求解主流程的装配口径）
     * @param solveTaskRepository 求解任务仓储
     * @param queryRepository 重放查询（只读 config）
     * @param justificationRepository 既有归因仓储
     */
    public InfeasibilityAnalyzer(ScheduleProblemAssembler assembler,
                                 SolveTaskRepository solveTaskRepository,
                                 InfeasibilityQueryRepository queryRepository,
                                 ConstraintJustificationRepository justificationRepository) {
        this.assembler = assembler;
        this.solveTaskRepository = solveTaskRepository;
        this.queryRepository = queryRepository;
        this.justificationRepository = justificationRepository;
    }

    /**
     * 求解结束钩子入口。
     *
     * @param event 终态事件
     * @return 归因结果
     */
    public InfeasibilityAnalysis analyze(SolveFinishEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (event.solved() && event.scoreHard() != null && event.scoreHard() >= 0) {
            return noEvidence(event.taskId(), null, List.of("求解成功且 hard=0，不存在不可行证据"));
        }
        UUID tenantId = event.tenantId() == null ? TenantContext.getTenantId() : event.tenantId();
        if (event.problem() != null) {
            List<JustificationRow> justifications = loadJustifications(tenantId, event.taskId());
            boolean calendarFallback = Boolean.TRUE.equals(event.metrics().get("calendarFallback"));
            AnalyzeInput input = new AnalyzeInput(event.taskId(), event.status(), event.scoreHard(),
                    event.scoreSoft(), event.metrics(), event.problem(), calendarFallback, true,
                    justifications, null, null);
            return withTenant(tenantId, () -> evaluate(input));
        }
        return withTenant(tenantId, () -> evaluate(buildInput(tenantId, event.taskId(), event.status(),
                event.scoreHard(), event.scoreSoft(), event.metrics())));
    }

    /**
     * 按任务主键分析（供 B2 查询端点复用）。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 归因结果；任何重放失败都降级为 {@code NO_EVIDENCE}
     */
    public InfeasibilityAnalysis analyzeTask(UUID tenantId, long solveTaskId) {
        try {
            Optional<SolveTaskRecord> record = solveTaskRepository.findById(tenantId, solveTaskId);
            if (record.isEmpty()) {
                return noEvidence(solveTaskId, null, List.of("solve_task 不存在，无法归因"));
            }
            Map<String, Object> metrics = parseMap(record.get().metricsJson());
            return withTenant(tenantId, () -> evaluate(buildInput(tenantId, solveTaskId,
                    record.get().status(), record.get().scoreHard(), record.get().scoreSoft(), metrics)));
        } catch (RuntimeException exception) {
            LOGGER.warn("不可行归因失败（降级为 NO_EVIDENCE）: taskId={}", solveTaskId, exception);
            return noEvidence(solveTaskId, null, List.of("归因失败: " + exception.getMessage()));
        }
    }

    private AnalyzeInput buildInput(UUID tenantId, long solveTaskId, String status, Long scoreHard,
                                    Long scoreSoft, Map<String, Object> metrics) {
        Optional<SolveTaskReplayRow> row = queryRepository.findReplayInput(tenantId, solveTaskId);
        if (row.isEmpty()) {
            return new AnalyzeInput(solveTaskId, status, scoreHard, scoreSoft, metrics, null,
                    false, false, List.of(), null, "solve_task 不存在或已删除，无法重放题面");
        }
        String failure = null;
        ScheduleProblem problem = null;
        Long candidateSeed;
        List<JustificationRow> justifications = loadJustifications(tenantId, solveTaskId);
        try {
            SolveConfig config = parseConfig(row.get().configJson());
            List<String> orderNos = parseOrderNos(row.get().configJson());
            SolveRequest request = new SolveRequest(null, row.get().sandboxId(), row.get().strategyId(),
                    row.get().engine(), orderNos, config.parameters());
            problem = assembler.assemble(request, config).problem();
            candidateSeed = parseCandidateSeed(row.get().configJson());
        } catch (RuntimeException exception) {
            candidateSeed = null;
            failure = "题面重放失败: " + exception.getMessage();
            LOGGER.warn("不可行归因重放失败: taskId={}", solveTaskId, exception);
        }
        boolean calendarFallback = Boolean.TRUE.equals(metrics.get("calendarFallback"));
        return new AnalyzeInput(solveTaskId, status, scoreHard, scoreSoft, metrics, problem,
                calendarFallback, false, justifications, candidateSeed, failure);
    }

    private List<JustificationRow> loadJustifications(UUID tenantId, long solveTaskId) {
        try {
            return justificationRepository.findBySolveTaskId(tenantId, solveTaskId);
        } catch (RuntimeException exception) {
            LOGGER.warn("读取既有归因失败（按空处理）: taskId={}", solveTaskId, exception);
            return List.of();
        }
    }

    /**
     * 纯函数：由输入构造报告（便于单测，不触库）。
     *
     * @param input 归因输入
     * @return 归因结果
     */
    static InfeasibilityAnalysis evaluate(AnalyzeInput input) {
        Objects.requireNonNull(input, "input 不能为空");
        if (STATUS_SOLVED.equals(input.status())
                && input.scoreHard() != null && input.scoreHard() >= 0) {
            return noEvidence(input.solveTaskId(), input.candidateSeed(),
                    List.of("求解成功且 hard=0，不存在不可行证据"));
        }
        if (input.problem() == null) {
            String reason = input.replayFailure() == null ? "题面不可用，无法采集证据" : input.replayFailure();
            return noEvidence(input.solveTaskId(), input.candidateSeed(), List.of(reason));
        }
        EvidenceBundle bundle = EvidenceCollector.collect(input.problem(), input.justifications(),
                input.calendarFallback());
        List<RepairCandidate> repairs = RepairAdvisor.advise(bundle, input.problem());
        List<String> assumptions = new ArrayList<>(bundle.assumptions());
        assumptions.add(input.fromEvent()
                ? "题面来源：终态事件 problem 引用（无重放漂移）"
                : "题面来源：solve_task.config 重放");
        appendRunAssumptions(assumptions, input);
        InfeasibilityVerdict verdict = decideVerdict(input.status(), bundle);
        if (verdict == InfeasibilityVerdict.NO_EVIDENCE) {
            return new InfeasibilityAnalysis(new InfeasibilityReport(input.solveTaskId(),
                    InfeasibilityVerdict.NO_EVIDENCE, List.of(), List.of(), List.of(), List.of(),
                    assumptions, null, null, input.candidateSeed(), false, null,
                    InfeasibilityReport.LlmStatus.DISABLED), repairs);
        }
        String narrative = narrative(verdict, bundle, input);
        InfeasibilityReport report = new InfeasibilityReport(input.solveTaskId(), verdict,
                bundle.blockingTasks(), bundle.conflictPairs(), bundle.resourceWindows(),
                bundle.violatedRules(), assumptions, OffsetDateTime.now(ZoneOffset.UTC), null,
                input.candidateSeed(), bundle.truncated(), narrative,
                InfeasibilityReport.LlmStatus.DISABLED);
        return new InfeasibilityAnalysis(report, repairs);
    }

    /**
     * verdict 判定纪律：没有结构性证明绝不报 {@code NO_FEASIBLE_SOLUTION_FOUND}。
     *
     * @param status 终态
     * @param bundle 证据集合
     * @return verdict
     */
    static InfeasibilityVerdict decideVerdict(String status, EvidenceBundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            return InfeasibilityVerdict.NO_EVIDENCE;
        }
        boolean runFailed = STATUS_FAILED.equals(status) || STATUS_TIMEOUT.equals(status);
        if (runFailed && bundle.infeasibilityProven()) {
            return InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND;
        }
        return InfeasibilityVerdict.PARTIAL_EVIDENCE;
    }

    private static void appendRunAssumptions(List<String> assumptions, AnalyzeInput input) {
        assumptions.add("社区版无 ConstraintMatchTotal：score 只能提供 hard/soft 总量，无法按约束拆分");
        if (input.scoreHard() != null || input.scoreSoft() != null) {
            assumptions.add("本次 scoreHard=" + input.scoreHard() + ", scoreSoft=" + input.scoreSoft());
        }
        appendMetric(assumptions, input.metrics(), "terminationReason");
        appendMetric(assumptions, input.metrics(), "unassignedTaskCount");
        appendMetric(assumptions, input.metrics(), "persistedTaskCount");
        if (input.replayFailure() != null) {
            assumptions.add(input.replayFailure());
        }
    }

    private static void appendMetric(List<String> assumptions, Map<String, Object> metrics, String key) {
        if (metrics == null) {
            return;
        }
        Object value = metrics.get(key);
        if (value != null) {
            assumptions.add("本次 " + key + "=" + value);
        }
    }

    private static String narrative(InfeasibilityVerdict verdict, EvidenceBundle bundle, AnalyzeInput input) {
        String counts = "阻塞任务 " + bundle.blockingTasks().size() + " 条 / 冲突对 "
                + bundle.conflictPairs().size() + " 对 / 资源缺口 " + bundle.resourceWindows().size()
                + " 条 / 违反规则 " + bundle.violatedRules().size() + " 条";
        if (verdict == InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND) {
            List<String> proofs = bundle.proofNotes();
            List<String> head = proofs.size() <= NARRATIVE_PROOF_LIMIT
                    ? proofs : proofs.subList(0, NARRATIVE_PROOF_LIMIT);
            return "结论：当前题面存在结构性不可行证据（" + counts + "）。证明：" + String.join("；", head);
        }
        if (STATUS_SOLVED.equals(input.status())) {
            return "结论：求解成功，但存在软约束违反（" + counts + "）；不构成不可行证据。";
        }
        return "结论：本次未找到可行解，但只收集到部分确定性证据（" + counts
                + "），不能据此断言不可行。";
    }

    private static InfeasibilityAnalysis noEvidence(long solveTaskId, Long candidateSeed,
                                                    List<String> assumptions) {
        InfeasibilityReport report = new InfeasibilityReport(solveTaskId,
                InfeasibilityVerdict.NO_EVIDENCE, List.of(), List.of(), List.of(), List.of(),
                assumptions, null, null, candidateSeed, false, null,
                InfeasibilityReport.LlmStatus.DISABLED);
        return new InfeasibilityAnalysis(report, List.of());
    }

    private static SolveConfig parseConfig(String configJson) {
        JsonNode root = readTree(configJson);
        Map<String, Object> parameters = Map.of();
        JsonNode parameterNode = root.get("parameters");
        if (parameterNode != null && parameterNode.isObject()) {
            parameters = OBJECT_MAPPER.convertValue(parameterNode,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        }
        return new SolveConfig(
                text(root, "engine", null),
                intValue(root, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS),
                intValue(root, "moveThreadCount", 0),
                text(root, "acceptor", DEFAULT_ACCEPTOR),
                longValue(root, "randomSeed", 0L),
                longValue(root, "maximumStepCount", 0L),
                intValue(root, "unimprovedSecondsSpentLimit", DEFAULT_UNIMPROVED_SECONDS),
                text(root, "direction", DEFAULT_DIRECTION).toUpperCase(java.util.Locale.ROOT),
                booleanValue(root, "useFrozenAssignments", true),
                parameters);
    }

    private static List<String> parseOrderNos(String configJson) {
        JsonNode root = readTree(configJson);
        JsonNode node = root.get("orderNos");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> orderNos = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                orderNos.add(item.asText());
            }
        }
        return List.copyOf(orderNos);
    }

    private static Long parseCandidateSeed(String configJson) {
        JsonNode root = readTree(configJson);
        JsonNode node = root.get("randomSeed");
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOGGER.warn("metrics JSON 解析失败（按空处理）", exception);
            return Map.of();
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return OBJECT_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("solve_task.config 不是合法 JSON", exception);
        }
    }

    private static String text(JsonNode root, String key, String fallback) {
        JsonNode node = root.get(key);
        return node == null || node.isNull() || !node.isTextual() ? fallback : node.asText();
    }

    private static int intValue(JsonNode root, String key, int fallback) {
        JsonNode node = root.get(key);
        return node == null || !node.canConvertToInt() ? fallback : node.asInt();
    }

    private static long longValue(JsonNode root, String key, long fallback) {
        JsonNode node = root.get(key);
        return node == null || !node.canConvertToLong() ? fallback : node.asLong();
    }

    private static boolean booleanValue(JsonNode root, String key, boolean fallback) {
        JsonNode node = root.get(key);
        return node == null || !node.isBoolean() ? fallback : node.asBoolean();
    }

    private <T> T withTenant(UUID tenantId, Supplier<T> action) {
        boolean explicit = TenantContext.hasExplicitTenant();
        UUID previous = explicit ? TenantContext.getTenantId() : null;
        TenantContext.setTenantId(tenantId);
        try {
            return action.get();
        } finally {
            if (explicit) {
                TenantContext.setTenantId(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * 归因输入（纯函数 evaluate 的入参）。
     *
     * @param solveTaskId 求解任务主键
     * @param status 终态
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metrics 度量
     * @param problem 重放题面，可空
     * @param calendarFallback 是否发生日历回退
     * @param fromEvent 题面是否直接来自终态事件（否则为 config 重放）
     * @param justifications 既有归因行
     * @param candidateSeed 复现 seed，可空
     * @param replayFailure 重放失败原因，可空
     */
    record AnalyzeInput(
            long solveTaskId,
            String status,
            Long scoreHard,
            Long scoreSoft,
            Map<String, Object> metrics,
            ScheduleProblem problem,
            boolean calendarFallback,
            boolean fromEvent,
            List<JustificationRow> justifications,
            Long candidateSeed,
            String replayFailure
    ) {
    }
}
