package com.hengce.aps.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.agent.checkpoint.AgentRunRecord;
import com.hengce.aps.agent.checkpoint.AgentRunStatus;
import com.hengce.aps.agent.checkpoint.CheckpointStore;
import com.hengce.aps.agent.llm.LlmDegradationReason;
import com.hengce.aps.agent.llm.LlmGateway;
import com.hengce.aps.agent.llm.LlmRequest;
import com.hengce.aps.agent.llm.LlmResult;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 归因解释服务：契约只给 {@code taskId}（排产任务主键），而归因按 {@code solve_task} 持久化。
 *
 * <p>映射规则：通过 {@code constraint_justification.implicated_facts} 反查包含该任务的归因行。
 * <ul>
 *   <li>0 条：返回 {@code available=false} + {@code NO_JUSTIFICATION_RECORD}，不编造；</li>
 *   <li>命中多个 solve_task：返回 {@code available=false} + {@code AMBIGUOUS_SOLVE_TASK} 与候选，
 *       禁止猜测；</li>
 *   <li>唯一 solve_task：返回该任务的约束证据与对应求解任务评分。</li>
 * </ul>
 * 求解任务不存在（唯一映射后）仍按既有语义返回 404/40400。</p>
 */
@Service
public class AgentExplainService {

    /**
     * 产品定稿的“无模型”降级文案，不得自行改写。
     */
    private static final String LLM_DISABLED_NARRATIVE =
            "AI 解读未启用（管理员未配置模型服务）。下方为求解结果中的原始归因，可正常使用。";

    /**
     * 产品定稿的“无归因”文案，不得自行改写。
     */
    private static final String NO_JUSTIFICATION_NARRATIVE =
            "该求解任务没有归因记录。归因由求解结果写入，早期求解可能没有记录。";

    private static final AgentExplainView.LlmStatus LLM_NOT_CONFIGURED =
            new AgentExplainView.LlmStatus(false, null, "NO_PROVIDER_CONFIGURED", "EVIDENCE");

    /**
     * 产品定稿的“超时”降级文案，不得自行改写。
     */
    private static final String LLM_TIMEOUT_NARRATIVE =
            "AI 解读超时（超过配置的超时时间）。原始归因不受影响，可重试。";

    /**
     * 模型调用失败（非超时）时的降级文案。
     */
    private static final String LLM_UNAVAILABLE_NARRATIVE =
            "AI 解读暂不可用（模型服务调用失败）。下方为求解结果中的原始归因，可正常使用。";

    /**
     * 模型输出无法解析时的降级文案。
     */
    private static final String LLM_INVALID_NARRATIVE =
            "AI 解读暂不可用（模型返回内容无法解析）。下方为求解结果中的原始归因，可正常使用。";

    private static final String PROMPT_KEY = "agent.explain";

    private static final int NARRATIVE_LIMIT = 600;

    private static final int MAX_OUTPUT_TOKENS = 800;

    private static final Logger LOG = LoggerFactory.getLogger(AgentExplainService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SolveTaskLifecycleService lifecycleService;

    private final ConstraintJustificationRepository justificationRepository;

    private final PlanVersionRepository planVersionRepository;

    private final ObjectProvider<LlmGateway> llmGatewayProvider;

    private final ObjectProvider<CheckpointStore> checkpointStoreProvider;

    /**
     * 构造服务。
     *
     * @param lifecycleService 求解任务生命周期服务
     * @param justificationRepository 归因持久层
     * @param planVersionRepository 计划版本持久层
     * @param llmGatewayProvider LLM 网关；未装配 Agent 模块时为空
     * @param checkpointStoreProvider 运行记录存储；未装配时为空
     */
    public AgentExplainService(SolveTaskLifecycleService lifecycleService,
                               ConstraintJustificationRepository justificationRepository,
                               PlanVersionRepository planVersionRepository,
                               ObjectProvider<LlmGateway> llmGatewayProvider,
                               ObjectProvider<CheckpointStore> checkpointStoreProvider) {
        this.lifecycleService = lifecycleService;
        this.justificationRepository = justificationRepository;
        this.planVersionRepository = planVersionRepository;
        this.llmGatewayProvider = llmGatewayProvider;
        this.checkpointStoreProvider = checkpointStoreProvider;
    }

    /**
     * 生成确定性归因解释。
     *
     * @param taskId 排产任务主键
     * @return 解释视图；无法唯一定位时 {@code available=false}
     */
    public AgentExplainView explain(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        List<JustificationRow> rows = justificationRepository.findByAffectedTaskId(tenantId, taskId);
        if (rows.isEmpty()) {
            return unavailable(taskId, "NO_JUSTIFICATION_RECORD", List.of(),
                    "任务 " + taskId + " 未出现在任何归因记录中，无法定位到求解任务。",
                    NO_JUSTIFICATION_NARRATIVE);
        }

        List<Long> solveTaskIds = rows.stream().map(JustificationRow::solveTaskId).distinct().sorted().toList();
        if (solveTaskIds.size() > 1) {
            return unavailable(taskId, "AMBIGUOUS_SOLVE_TASK", solveTaskIds,
                    "任务 " + taskId + " 出现在 " + solveTaskIds.size() + " 个求解任务的归因中（"
                            + solveTaskIds + "），无法唯一定位，未做任何猜测。",
                    "该任务关联多个求解任务，无法唯一定位归因；请改用求解任务主键查询。");
        }

        long solveTaskId = solveTaskIds.get(0);
        SolveTaskDetail detail = lifecycleService.detail(solveTaskId);
        Long unassigned = asLong(
                detail.metrics() == null ? null : detail.metrics().get("unassignedTaskCount"));

        List<AgentExplainView.ConstraintImpact> impacts = new ArrayList<>(rows.size());
        List<String> justificationIds = new ArrayList<>(rows.size());
        List<AgentExplainView.ExplainCause> causes = new ArrayList<>(rows.size());
        int hardCount = 0;
        int softCount = 0;
        BigDecimal softDeduction = BigDecimal.ZERO;

        for (JustificationRow row : rows) {
            justificationIds.add(row.id().toString());
            boolean hard = "HARD".equalsIgnoreCase(row.scoreImpact());
            if (hard) {
                hardCount++;
            } else {
                softCount++;
                if (row.deduction() != null) {
                    softDeduction = softDeduction.add(row.deduction());
                }
            }
            String description = row.description();
            if (description == null || description.isBlank()) {
                description = derivedDescription(row);
            }
            AgentExplainView.ConstraintImpact impact = new AgentExplainView.ConstraintImpact(
                    row.id(), row.constraintId(), row.constraintName(), row.scoreImpact(), hard,
                    row.weight(), row.deduction(), List.copyOf(row.taskIds()), description);
            impacts.add(impact);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("justificationId", row.id().toString());
            evidence.put("sourceSolveTaskId", solveTaskId);
            evidence.put("constraintId", row.constraintId());
            evidence.put("constraintName", row.constraintName());
            evidence.put("scoreImpact", row.scoreImpact());
            evidence.put("hard", hard);
            evidence.put("weight", row.weight());
            evidence.put("deduction", row.deduction());
            evidence.put("affectedTaskIds", List.copyOf(row.taskIds()));
            evidence.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
            causes.add(new AgentExplainView.ExplainCause(row.constraintId(), description, evidence));
        }

        List<AgentExplainView.PlanVersionEvidence> planVersions = planVersionRepository
                .findByTaskIds(tenantId, List.of(taskId), 1).stream()
                .map(AgentExplainService::toPlanVersionEvidence)
                .toList();

        String summary = String.format(
                "任务 %d 由求解任务 %d 归因（状态 %s）：共 %d 条约束证据（硬约束 %d 条，软约束 %d 条，"
                        + "软约束扣分合计 %s）；评分 hard=%s、soft=%s，未排产任务=%s。",
                taskId, solveTaskId, detail.status(), rows.size(), hardCount, softCount,
                formatScore(softDeduction), detail.scoreHard(), detail.scoreSoft(),
                unassigned == null ? "未知" : unassigned);

        LlmEnrichment enrichment = enrich(tenantId, taskId, solveTaskId, detail, rows);

        return new AgentExplainView(taskId, "TASK", true, enrichment.source(), null, solveTaskId, List.of(),
                detail.status(), detail.scoreHard(), detail.scoreSoft(), unassigned, detail.degraded(),
                detail.metrics(), justificationIds, impacts, planVersions, false, summary,
                enrichment.narrative(), causes, List.of(), enrichment.status());
    }

    /**
     * 调用模型生成解读；任何失败都退回确定性别名文案，不抛出。
     *
     * <p>提示词只允许使用证据 JSON 中出现的约束码/扣分/任务号；输出必须是含
     * {@code narrative} 的 JSON 对象，否则按 {@code INVALID_OUTPUT} 降级。</p>
     *
     * @param tenantId 租户
     * @param taskId 排产任务主键
     * @param solveTaskId 求解任务主键
     * @param detail 求解任务详情
     * @param rows 归因行
     * @return 解读与模型状态
     */
    private LlmEnrichment enrich(UUID tenantId, long taskId, long solveTaskId, SolveTaskDetail detail,
                                 List<JustificationRow> rows) {
        LlmGateway gateway = llmGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return new LlmEnrichment(LLM_DISABLED_NARRATIVE, "EVIDENCE", LLM_NOT_CONFIGURED);
        }
        LlmResult result = gateway.call(LlmRequest.json(
                buildPrompt(taskId, solveTaskId, detail, rows), MAX_OUTPUT_TOKENS));
        if (result.available()) {
            String narrative = validateNarrative(result.output());
            if (narrative != null) {
                recordRun(tenantId, taskId, solveTaskId, gateway, result);
                return new LlmEnrichment(narrative, "LLM", new AgentExplainView.LlmStatus(
                        true, result.provider(), null, null, result.latencyMillis(),
                        result.promptTokens(), result.completionTokens()));
            }
            result = LlmResult.degraded(result.provider(), LlmDegradationReason.INVALID_OUTPUT,
                    "model output is not a JSON object with non-blank narrative", result.latencyMillis());
        }
        recordRun(tenantId, taskId, solveTaskId, gateway, result);
        String reason = result.degradationReason() == null
                ? LlmDegradationReason.PROVIDER_ERROR.name() : result.degradationReason().name();
        String provider = result.provider() == null || result.provider().isBlank() ? null : result.provider();
        return new LlmEnrichment(narrativeFor(result.degradationReason()), "EVIDENCE",
                new AgentExplainView.LlmStatus(gateway.configured(), provider, reason, "EVIDENCE",
                        result.latencyMillis(), result.promptTokens(), result.completionTokens()));
    }

    /**
     * 组装只含真实证据的提示词。
     *
     * @param taskId 排产任务主键
     * @param solveTaskId 求解任务主键
     * @param detail 求解任务详情
     * @param rows 归因行
     * @return 提示词
     */
    private static String buildPrompt(long taskId, long solveTaskId, SolveTaskDetail detail,
                                      List<JustificationRow> rows) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("taskId", taskId);
        evidence.put("solveTaskId", solveTaskId);
        evidence.put("scoreHard", detail.scoreHard());
        evidence.put("scoreSoft", detail.scoreSoft());
        List<Map<String, Object>> constraints = new ArrayList<>(rows.size());
        for (JustificationRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("constraintId", row.constraintId());
            item.put("constraintName", row.constraintName());
            item.put("scoreImpact", row.scoreImpact());
            item.put("deduction", row.deduction());
            item.put("affectedTaskIds", row.taskIds());
            constraints.add(item);
        }
        evidence.put("constraints", constraints);
        String json;
        try {
            json = JSON.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            json = "{}";
        }
        return "你是 APS 排产归因解读助手。\n"
                + "1. 只能使用 EVIDENCE 中出现的 constraintId、constraintName、deduction、scoreImpact、"
                + "taskId、solveTaskId、scoreHard、scoreSoft。\n"
                + "2. 禁止出现 EVIDENCE 中不存在的约束、任务、资源、数字或原因；禁止推测。\n"
                + "3. narrative 只陈述证据；不要给处置建议，不要推断排产可行性、交期或成本。\n"
                + "4. 只输出一个 JSON 对象，不要 Markdown 代码块，不要解释过程。\n"
                + "输出格式：{\"narrative\":\"不超过 120 字的中文解读\"}\n"
                + "EVIDENCE:\n" + json;
    }

    /**
     * 校验模型输出：必须是含非空 {@code narrative} 的 JSON 对象。
     *
     * @param output 模型原始输出
     * @return narrative；不合规为 null
     */
    private static String validateNarrative(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String text = output.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        text = text.substring(start, end + 1);
        try {
            JsonNode root = JSON.readTree(text);
            JsonNode node = root.path("narrative");
            if (!root.isObject() || !node.isTextual()) {
                return null;
            }
            String narrative = node.asText().trim();
            return narrative.isEmpty() || narrative.length() > NARRATIVE_LIMIT ? null : narrative;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String narrativeFor(LlmDegradationReason reason) {
        if (reason == null) {
            return LLM_UNAVAILABLE_NARRATIVE;
        }
        return switch (reason) {
            case NO_PROVIDER_CONFIGURED, PROVIDER_DISABLED -> LLM_DISABLED_NARRATIVE;
            case TIMEOUT -> LLM_TIMEOUT_NARRATIVE;
            case INVALID_OUTPUT, EMPTY_RESPONSE -> LLM_INVALID_NARRATIVE;
            default -> LLM_UNAVAILABLE_NARRATIVE;
        };
    }

    /**
     * 落一条 {@code agent_run}：provider/model/reason 与耗时、token；<b>绝不落密钥</b>。
     *
     * @param tenantId 租户
     * @param taskId 排产任务主键
     * @param solveTaskId 求解任务主键
     * @param gateway 网关（读取模型名）
     * @param result 调用结果
     */
    private void recordRun(UUID tenantId, long taskId, long solveTaskId, LlmGateway gateway,
                           LlmResult result) {
        CheckpointStore store = checkpointStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("taskId", taskId);
            variables.put("solveTaskId", solveTaskId);
            variables.put("model", gateway.configuredModel());
            variables.put("latencyMillis", result.latencyMillis());
            variables.put("promptTokens", result.promptTokens());
            variables.put("completionTokens", result.completionTokens());
            AgentRunRecord run = store.createRun(tenantId, PROMPT_KEY,
                    JSON.writeValueAsString(variables));
            if (result.available()) {
                store.updateRun(run.id(), AgentRunStatus.COMPLETED, null, result.provider(), null, 4, null);
            } else {
                store.updateRun(run.id(), AgentRunStatus.DEGRADED, null, result.provider(),
                        result.degradationReason(), 4, result.degradationDetail());
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            LOG.warn("event=agent.explain.run_record_failed type={}",
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * 模型解读结果。
     *
     * @param narrative 解读文本
     * @param source {@code LLM} 或 {@code EVIDENCE}
     * @param status 模型状态
     */
    private record LlmEnrichment(String narrative, String source, AgentExplainView.LlmStatus status) {
    }

    private static AgentExplainView unavailable(long taskId, String reason, List<Long> candidates,
                                                String summary, String narrative) {
        return new AgentExplainView(taskId, "TASK", false, "EVIDENCE", reason, null, candidates,
                null, null, null, null, false, Map.of(), List.of(), List.of(), List.of(), false,
                summary, narrative, List.of(), List.of(), LLM_NOT_CONFIGURED);
    }

    private static String derivedDescription(JustificationRow row) {
        StringBuilder builder = new StringBuilder();
        builder.append(row.constraintName() == null ? row.constraintId() : row.constraintName());
        builder.append("（").append(row.scoreImpact() == null ? "UNKNOWN" : row.scoreImpact());
        if (row.weight() != null) {
            builder.append("，权重 ").append(row.weight());
        }
        if (row.deduction() != null) {
            builder.append("，扣分 ").append(formatScore(row.deduction()));
        }
        builder.append("）");
        return builder.toString();
    }

    private static AgentExplainView.PlanVersionEvidence toPlanVersionEvidence(PlanVersionRow row) {
        return new AgentExplainView.PlanVersionEvidence(row.id(), row.planNo(), row.versionNo(),
                row.status(), row.publishedAt(), row.taskCount());
    }

    private static String formatScore(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
