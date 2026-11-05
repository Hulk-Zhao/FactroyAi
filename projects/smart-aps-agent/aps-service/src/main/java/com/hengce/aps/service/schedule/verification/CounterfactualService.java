package com.hengce.aps.service.schedule.verification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.FrozenTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityQueryRepository.SolveTaskReplayRow;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.infeasibility.EvidenceBundle;
import com.hengce.aps.service.schedule.infeasibility.EvidenceCollector;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalysis;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityAnalyzer;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.counterfactual.CounterfactualKind;
import com.hengce.aps.solver.api.counterfactual.CounterfactualRequest;
import com.hengce.aps.solver.api.counterfactual.CounterfactualResult;
import com.hengce.aps.solver.api.counterfactual.CounterfactualStatus;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C52 反事实修复验证。
 *
 * <p>流程：读归因报告（B1 内核，不依赖 B2 落库表）→ 按 solve_task.config 重放题面 →
 * 应用最小改动 → 走 {@link VerificationSolver} 只读重解 → 按契约翻译结论。</p>
 *
 * <p><b>纪律</b>：只调用求解器与只读查询，不写计划；无解时只给证据，不给建议日期。</p>
 */
@Service
public class CounterfactualService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CounterfactualService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int MIN_TIMEOUT_SECONDS = 1;

    private static final int MAX_TIMEOUT_SECONDS = 10;

    private static final int MAX_CHANGES = 20;

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private static final int DEFAULT_UNIMPROVED_SECONDS = 10;

    private static final String DEFAULT_ACCEPTOR = "LATE_ACCEPTANCE";

    private static final String DEFAULT_DIRECTION = "FORWARD";

    private final ScheduleProblemAssembler assembler;

    private final InfeasibilityQueryRepository queryRepository;

    private final InfeasibilityAnalyzer infeasibilityAnalyzer;

    private final SolveTaskRepository solveTaskRepository;

    private final ScheduleRepository scheduleRepository;

    private final VerificationSolver verificationSolver;

    /**
     * 构造反事实服务。
     *
     * @param assembler 题面装配器（只读取）
     * @param queryRepository 重放查询（只读 solve_task.config）
     * @param infeasibilityAnalyzer B1 归因内核
     * @param solveTaskRepository 求解任务读取
     * @param scheduleRepository 任务读取（仅用于 Change.taskId）
     * @param verificationSolver 不落库验证求解接缝
     */
    public CounterfactualService(ScheduleProblemAssembler assembler,
                                 InfeasibilityQueryRepository queryRepository,
                                 InfeasibilityAnalyzer infeasibilityAnalyzer,
                                 SolveTaskRepository solveTaskRepository,
                                 ScheduleRepository scheduleRepository,
                                 VerificationSolver verificationSolver) {
        this.assembler = assembler;
        this.queryRepository = queryRepository;
        this.infeasibilityAnalyzer = infeasibilityAnalyzer;
        this.solveTaskRepository = solveTaskRepository;
        this.scheduleRepository = scheduleRepository;
        this.verificationSolver = verificationSolver;
    }

    /**
     * 执行反事实验证。
     *
     * @param solveTaskId 原求解任务主键
     * @param request 修复类型与参数
     * @return C52 响应；{@code mainPlanWritten} 恒 false
     * @throws BizException 任务不存在（404）或参数非法（422）
     */
    public CounterfactualResult verify(long solveTaskId, CounterfactualRequest request) {
        validateRequest(request);
        UUID tenantId = TenantContext.getTenantId();
        if (solveTaskRepository.findById(tenantId, solveTaskId).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "solve_task 不存在：" + solveTaskId);
        }
        InfeasibilityAnalysis analysis = infeasibilityAnalyzer.analyzeTask(tenantId, solveTaskId);
        Optional<SolveTaskReplayRow> replay = queryRepository.findReplayInput(tenantId, solveTaskId);
        if (replay.isEmpty()) {
            return unverifiedResult(solveTaskId, request, List.of(), 0,
                    "solve_task.config 不可用，无法重放题面（未改动、未求解）");
        }
        ReplayInput input;
        try {
            input = withTenant(tenantId, () -> buildReplayInput(replay.get()));
        } catch (RuntimeException exception) {
            LOGGER.warn("C52 题面重放失败: taskId={}", solveTaskId, exception);
            return unverifiedResult(solveTaskId, request,
                    assumptionsOf(analysis, List.of()), 0,
                    "题面重放失败：" + exception.getMessage());
        }
        CounterfactualMutationApplier.Mutation mutation;
        try {
            mutation = applyMutation(request, input.problem());
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return unverifiedResult(solveTaskId, request, assumptionsOf(analysis, List.of()), 0,
                    "反事实改动失败：" + exception.getMessage());
        }
        if (!mutation.applicable()) {
            return unverifiedResult(solveTaskId, request,
                    assumptionsOf(analysis, mutation.notes()), mutation.affectedTaskCount(),
                    mutation.reason());
        }
        SolveConfig solveConfig = withTimeout(input.config(), request.timeoutSeconds());
        VerificationOutcome outcome = verificationSolver.solve(mutation.problem(), solveConfig);
        List<String> assumptions = assumptionsOf(analysis, mutation.notes());
        assumptions.add("预算：timeoutSeconds=" + request.timeoutSeconds()
                + "；预算耗尽返回 OUT_OF_BUDGET，不判不可行。");
        return translate(solveTaskId, request, input, mutation, outcome, assumptions);
    }

    private ReplayInput buildReplayInput(SolveTaskReplayRow row) {
        SolveConfig config = parseConfig(row.configJson());
        List<String> orderNos = parseOrderNos(row.configJson());
        SolveRequest request = new SolveRequest(null, row.sandboxId(), row.strategyId(),
                row.engine(), orderNos, config.parameters());
        ScheduleProblem problem = assembler.assemble(request, config).problem();
        return new ReplayInput(problem, config, row.sandboxId(), orderNos, parseSeed(row.configJson()));
    }

    private CounterfactualResult translate(long solveTaskId, CounterfactualRequest request,
                                           ReplayInput input,
                                           CounterfactualMutationApplier.Mutation mutation,
                                           VerificationOutcome outcome,
                                           List<String> assumptions) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        CounterfactualResult.Solver solver = outcome.status() == VerificationStatus.UNVERIFIED
                ? null
                : new CounterfactualResult.Solver(outcome.engine(), outcome.seed(),
                        outcome.hardScore(), outcome.softScore(), outcome.durationMs(),
                        outcome.terminationReason());
        if (outcome.status() == VerificationStatus.FEASIBLE) {
            ChangeSet changeSet = buildChanges(input, input.problem(), outcome.assignments());
            String narrative = "反事实（" + request.kind() + "）验证可行：最小改动后重解 hard=0，目标工序 "
                    + outcome.assignedTaskCount() + "/" + outcome.totalTaskCount()
                    + " 全部已分配；changes 的日期/资源均来自本次求解结果。";
            String message = "改动后重解可行，未写主计划。";
            return new CounterfactualResult(solveTaskId, request.kind(),
                    CounterfactualStatus.VERIFIED_FEASIBLE, true, mutation.affectedTaskCount(), 0,
                    changeSet.changes(), changeSet.truncated(), solver, null, assumptions,
                    narrative, message, now, false);
        }
        if (outcome.status() == VerificationStatus.INFEASIBLE) {
            InfeasibilityReport evidence = buildEvidence(solveTaskId, mutation.problem(),
                    outcome, input.seed(), assumptions);
            String narrative = "反事实（" + request.kind() + "）重解仍不可行：hard="
                    + (outcome.hardScore() == null ? "-" : outcome.hardScore().toPlainString())
                    + "，未分配 " + outcome.unassignedTaskCount() + " 道工序；证据来自本次重解。";
            String message = "改动后仍未找到可行解，未给出建议日期。";
            return new CounterfactualResult(solveTaskId, request.kind(),
                    CounterfactualStatus.STILL_INFEASIBLE, false, mutation.affectedTaskCount(),
                    outcome.unassignedTaskCount(), List.of(), false, solver, evidence, assumptions,
                    narrative, message, now, false);
        }
        if (outcome.status() == VerificationStatus.OUT_OF_BUDGET) {
            String message = "验证求解超出预算（" + request.timeoutSeconds()
                    + " 秒），未形成可行性结论；预算耗尽不等于不可行。";
            return new CounterfactualResult(solveTaskId, request.kind(),
                    CounterfactualStatus.OUT_OF_BUDGET, false, mutation.affectedTaskCount(), null,
                    List.of(), false, solver, null, assumptions, message, message, now, false);
        }
        return unverifiedResult(solveTaskId, request, assumptions, mutation.affectedTaskCount(),
                outcome.message());
    }

    private CounterfactualResult unverifiedResult(long solveTaskId, CounterfactualRequest request,
                                                  List<String> assumptions, int affectedTaskCount,
                                                  String reason) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        return new CounterfactualResult(solveTaskId, request.kind(),
                CounterfactualStatus.UNVERIFIED, false, affectedTaskCount, null, List.of(), false,
                null, null, assumptions, null, reason, now, false);
    }

    private CounterfactualMutationApplier.Mutation applyMutation(CounterfactualRequest request,
                                                                ScheduleProblem problem) {
        CounterfactualKind kind = request.kind();
        Map<String, Object> params = request.params();
        if (kind == CounterfactualKind.UNFREEZE_TASKS) {
            return CounterfactualMutationApplier.unfreeze(problem, requireLongList(params, "taskIds"));
        }
        if (kind == CounterfactualKind.EXTEND_CALENDAR) {
            return CounterfactualMutationApplier.extendCalendar(problem,
                    requireUuid(params, "resourceId"),
                    requireInstant(params, "from"),
                    requireInstant(params, "to"));
        }
        if (kind == CounterfactualKind.ADD_RESOURCE) {
            requireString(params, "resourceCode", kind);
            return unsupported(kind);
        }
        if (kind == CounterfactualKind.RELAX_DUE_DATE) {
            requireStringList(params, "orderNos", kind);
            requirePositiveInt(params, "days", kind, 1);
            return unsupported(kind);
        }
        if (kind == CounterfactualKind.SPLIT_BATCH) {
            requireString(params, "orderNo", kind);
            requirePositiveInt(params, "batchCount", kind, 2);
            return unsupported(kind);
        }
        requireString(params, "materialCode", kind);
        requireString(params, "substituteCode", kind);
        return unsupported(kind);
    }

    private static CounterfactualMutationApplier.Mutation unsupported(CounterfactualKind kind) {
        return CounterfactualMutationApplier.Mutation.unavailable(
                kind + " 在本次题面中缺少可直接应用的字段（不编造资源/批次/替代料），未做改动");
    }

    private ChangeSet buildChanges(ReplayInput input, ScheduleProblem problem,
                                   List<TaskAssignment> assignments) {
        Map<String, Long> taskIds = new HashMap<>();
        for (TaskAssignment assignment : problem.frozenAssignments()) {
            if (assignment.taskId() != null) {
                taskIds.putIfAbsent(key(assignment.orderId(), assignment.operationIndex()),
                        assignment.taskId());
            }
        }
        for (TaskAssignment assignment : problem.initialAssignments()) {
            if (assignment.taskId() != null) {
                taskIds.putIfAbsent(key(assignment.orderId(), assignment.operationIndex()),
                        assignment.taskId());
            }
        }
        List<UUID> orderIds = problem.orders().stream().map(SchedulingOrder::orderId).toList();
        try {
            List<FrozenTaskRow> rows = scheduleRepository.findTasksByOrders(
                    TenantContext.getTenantId(), input.sandboxId(), orderIds);
            if (rows != null) {
                for (FrozenTaskRow row : rows) {
                    taskIds.putIfAbsent(key(row.orderId(), row.operationIndex()), row.taskId());
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("C52 读取既有 taskId 失败（仅影响 Change 输出）", exception);
        }
        List<CounterfactualResult.Change> changes = new ArrayList<>();
        for (TaskAssignment assignment : assignments) {
            Long taskId = taskIds.get(key(assignment.orderId(), assignment.operationIndex()));
            if (taskId == null) {
                continue;
            }
            changes.add(new CounterfactualResult.Change(taskId, assignment.orderNo(),
                    assignment.operationIndex(), assignment.resourceCode(),
                    assignment.startTime(), assignment.endTime()));
        }
        changes.sort(Comparator
                .comparing(CounterfactualResult.Change::startTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CounterfactualResult.Change::orderNo,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(CounterfactualResult.Change::operationIndex));
        List<CounterfactualResult.Change> capped = changes.size() <= MAX_CHANGES
                ? List.copyOf(changes)
                : List.copyOf(changes.subList(0, MAX_CHANGES));
        return new ChangeSet(capped, changes.size() > MAX_CHANGES);
    }

    private InfeasibilityReport buildEvidence(long solveTaskId, ScheduleProblem problem,
                                              VerificationOutcome outcome, Long seed,
                                              List<String> assumptions) {
        EvidenceBundle bundle = EvidenceCollector.collect(problem,
                justificationsAsRows(solveTaskId, outcome.justifications()), false);
        InfeasibilityVerdict verdict = bundle.isEmpty()
                ? InfeasibilityVerdict.NO_EVIDENCE
                : (bundle.infeasibilityProven()
                        ? InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND
                        : InfeasibilityVerdict.PARTIAL_EVIDENCE);
        List<String> evidenceAssumptions = new ArrayList<>(bundle.assumptions());
        evidenceAssumptions.addAll(assumptions);
        evidenceAssumptions.add("证据来自反事实改动后的只读重解，未写主计划。");
        OffsetDateTime generatedAt = verdict == InfeasibilityVerdict.NO_EVIDENCE
                ? null : OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        String narrative = verdict == InfeasibilityVerdict.NO_EVIDENCE
                ? null
                : "重解后证据：阻塞任务 " + bundle.blockingTasks().size() + " 条 / 冲突对 "
                        + bundle.conflictPairs().size() + " 对 / 资源缺口 "
                        + bundle.resourceWindows().size() + " 条。";
        return new InfeasibilityReport(solveTaskId, verdict, bundle.blockingTasks(),
                bundle.conflictPairs(), bundle.resourceWindows(), bundle.violatedRules(),
                evidenceAssumptions, generatedAt, null, seed, bundle.truncated(), narrative,
                InfeasibilityReport.LlmStatus.DISABLED);
    }

    /**
     * 把本次重解的约束归因转成证据采集器可消费的行（仅内存，不落库）。
     */
    private static List<JustificationRow> justificationsAsRows(long solveTaskId,
                                                              List<ConstraintJustification> items) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        List<JustificationRow> rows = new ArrayList<>(items.size());
        for (ConstraintJustification item : items) {
            rows.add(new JustificationRow(UUID.randomUUID(), solveTaskId, item.constraintCode(),
                    item.constraintCode(), item.type(), item.taskIds(), item.description(),
                    item.weight(), item.deduction(), now));
        }
        return List.copyOf(rows);
    }

    private List<String> assumptionsOf(InfeasibilityAnalysis analysis, List<String> notes) {
        List<String> assumptions = new ArrayList<>();
        if (analysis != null && analysis.report() != null) {
            assumptions.addAll(analysis.report().assumptions());
        }
        assumptions.add("题面来源：solve_task.config 重放（C52 只读反事实验证）。");
        assumptions.add("本次验证未写 plan_version/task/orders/solve_task；mainPlanWritten 恒 false。");
        assumptions.add("社区版单线程求解（moveThreadCount=NONE），耗时随题面规模线性增长。");
        assumptions.addAll(notes);
        return assumptions;
    }

    private static void validateRequest(CounterfactualRequest request) {
        if (request == null || request.kind() == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "kind 必填");
        }
        Integer timeout = request.timeoutSeconds();
        if (timeout == null || timeout < MIN_TIMEOUT_SECONDS || timeout > MAX_TIMEOUT_SECONDS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "timeoutSeconds 必须在 " + MIN_TIMEOUT_SECONDS + "-" + MAX_TIMEOUT_SECONDS + " 秒之间");
        }
    }

    private static Object requiredKey(Map<String, Object> params, String key, CounterfactualKind kind) {
        Object value = params.get(key);
        if (value == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 是 " + kind + " 的必填键");
        }
        return value;
    }

    private static UUID requireUuid(Map<String, Object> params, String key) {
        Object value = requiredKey(params, key, CounterfactualKind.EXTEND_CALENDAR);
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 必须是 uuid");
        }
    }

    private static OffsetDateTime requireInstant(Map<String, Object> params, String key) {
        Object value = requiredKey(params, key, CounterfactualKind.EXTEND_CALENDAR);
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 必须是 ISO-8601 日期时间（含时区）");
        }
    }

    private static List<Long> requireLongList(Map<String, Object> params, String key) {
        Object value = requiredKey(params, key, CounterfactualKind.UNFREEZE_TASKS);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 必须是非空整数数组");
        }
        List<Long> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            } else if (item instanceof String text && text.matches("-?\\d+")) {
                result.add(Long.parseLong(text));
            } else {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "params." + key + " 必须是非空整数数组");
            }
        }
        return List.copyOf(result);
    }

    private static String requireString(Map<String, Object> params, String key,
                                        CounterfactualKind kind) {
        Object value = requiredKey(params, key, kind);
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 不能为空");
        }
        return text;
    }

    private static List<String> requireStringList(Map<String, Object> params, String key,
                                                  CounterfactualKind kind) {
        Object value = requiredKey(params, key, kind);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 必须是非空字符串数组");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "params." + key + " 必须是非空字符串数组");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static int requirePositiveInt(Map<String, Object> params, String key,
                                          CounterfactualKind kind, int minimum) {
        Object value = requiredKey(params, key, kind);
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value instanceof String text && text.matches("-?\\d+")) {
            parsed = Integer.parseInt(text);
        } else {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 必须是整数");
        }
        if (parsed < minimum) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "params." + key + " 必须 >= " + minimum);
        }
        return parsed;
    }

    private static String key(UUID orderId, int operationIndex) {
        return orderId + "#" + operationIndex;
    }

    private static SolveConfig withTimeout(SolveConfig base, int timeoutSeconds) {
        return new SolveConfig(base.engine(), timeoutSeconds, base.moveThreadCount(), base.acceptor(),
                base.randomSeed(), base.maximumStepCount(), base.unimprovedSecondsSpentLimit(),
                base.direction(), base.useFrozenAssignments(), base.parameters());
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

    private static Long parseSeed(String configJson) {
        JsonNode root = readTree(configJson);
        JsonNode node = root.get("randomSeed");
        return node == null || node.isNull() ? null : node.asLong();
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

    private static <T> T withTenant(UUID tenantId, Supplier<T> action) {
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

    private record ReplayInput(ScheduleProblem problem, SolveConfig config, UUID sandboxId,
                               List<String> orderNos, Long seed) {
    }

    private record ChangeSet(List<CounterfactualResult.Change> changes, boolean truncated) {
    }
}
