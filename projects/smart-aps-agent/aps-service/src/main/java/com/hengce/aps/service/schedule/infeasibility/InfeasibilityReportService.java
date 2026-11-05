package com.hengce.aps.service.schedule.infeasibility;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository.SolveTaskRecord;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityReportRepository;
import com.hengce.aps.infrastructure.persistence.schedule.infeasibility.InfeasibilityReportRepository.InfeasibilityReportRow;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * C51 不可行证据查询与落库（B2）。
 *
 * <p>读取顺序：先查 {@code infeasibility_report}，命中直接返回（补充真实可得的
 * {@code solverRunId}）；未命中才调用 {@link InfeasibilityAnalyzer#analyzeTask} 现算，
 * 并在 {@code assumptions} 里标明“未落库”，不伪装成已持久化。</p>
 *
 * <p>返回前统一做 C51 形状归一：每类证据 <= 8 条、超限置 {@code truncated}；
 * {@code NO_EVIDENCE} 强制 {@code generatedAt/narrative = null}、{@code llmStatus = DISABLED}。</p>
 */
@Service
public class InfeasibilityReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InfeasibilityReportService.class);

    /**
     * C51 每类证据上限。
     */
    private static final int MAX_EVIDENCE_ITEMS = 8;

    /**
     * 现算路径的显式提示。
     */
    private static final String COMPUTED_ONLY_ASSUMPTION =
            "证据来源：查询时按 solve_task.config 现算，未落库（infeasibility_report 未命中）";

    /**
     * 只读 metrics JSON 解析器。
     */
    private static final ObjectMapper METRICS_MAPPER = new ObjectMapper();

    private final SolveTaskRepository solveTaskRepository;

    private final InfeasibilityAnalyzer analyzer;

    private final InfeasibilityReportRepository reportRepository;

    /**
     * 构造服务。
     *
     * @param solveTaskRepository 求解任务仓储
     * @param analyzer 归因分析器
     * @param reportRepository 不可行证据持久层
     */
    public InfeasibilityReportService(SolveTaskRepository solveTaskRepository,
                                      InfeasibilityAnalyzer analyzer,
                                      InfeasibilityReportRepository reportRepository) {
        this.solveTaskRepository = solveTaskRepository;
        this.analyzer = analyzer;
        this.reportRepository = reportRepository;
    }

    /**
     * 读取 C51 报告：先查落库，未命中再现算。
     *
     * @param tenantId 租户标识（显式传参，不依赖 ThreadLocal）
     * @param solveTaskId 求解任务主键
     * @return C51 报告；任务不存在时抛 {@link ErrorCode#NOT_FOUND}
     */
    public InfeasibilityReport report(UUID tenantId, long solveTaskId) {
        SolveTaskRecord record = solveTaskRepository.findById(tenantId, solveTaskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "求解任务不存在: " + solveTaskId));
        String metricsSolverRunId = solverRunId(record.metricsJson());
        Optional<InfeasibilityReportRow> stored = reportRepository.find(tenantId, solveTaskId);
        if (stored.isPresent()) {
            InfeasibilityReportRow row = stored.get();
            InfeasibilityReport parsed = InfeasibilityReportJson.fromJson(row.reportJson());
            return normalize(parsed, solveTaskId, row.generatedAt(), metricsSolverRunId);
        }
        InfeasibilityReport computed = analyzer.analyzeTask(tenantId, solveTaskId).report();
        return normalize(withComputedAssumption(computed), solveTaskId, null, metricsSolverRunId);
    }

    /**
     * 终态落库（按 {@code (tenant, solve_task_id)} upsert，可重复执行）。
     *
     * @param tenantId 租户标识（来自终态事件，不依赖 ThreadLocal）
     * @param solveTaskId 求解任务主键
     * @param analysis 归因结果
     */
    public void persist(UUID tenantId, long solveTaskId, InfeasibilityAnalysis analysis) {
        InfeasibilityReport report = normalize(analysis.report(), solveTaskId, null, null);
        reportRepository.upsert(tenantId, solveTaskId, report.verdict().name(), report.generatedAt(),
                report.candidateSeed(), report.truncated(), report.llmStatus().name(),
                report.narrative(), InfeasibilityReportJson.toJson(report),
                InfeasibilityReportJson.repairsToJson(analysis.repairCandidates()));
    }

    /**
     * 归一 C51 形状：截断到 8 条、补 null 纪律、补可得的 solverRunId。
     *
     * @param report 原始报告
     * @param solveTaskId 路径上的任务主键
     * @param storedGeneratedAt 落库列上的生成时间（JSON 缺失时兜底）
     * @param metricsSolverRunId 从 solve_task.metrics 读到的运行标识，可能为空
     * @return 归一后的报告
     */
    private static InfeasibilityReport normalize(InfeasibilityReport report, long solveTaskId,
                                                 OffsetDateTime storedGeneratedAt,
                                                 String metricsSolverRunId) {
        String solverRunId = report.solverRunId() != null ? report.solverRunId() : metricsSolverRunId;
        if (report.verdict() == InfeasibilityVerdict.NO_EVIDENCE) {
            return new InfeasibilityReport(solveTaskId, InfeasibilityVerdict.NO_EVIDENCE,
                    List.of(), List.of(), List.of(), List.of(), report.assumptions(),
                    null, solverRunId, report.candidateSeed(), report.truncated(), null,
                    InfeasibilityReport.LlmStatus.DISABLED);
        }
        boolean exceeded = report.blockingTasks().size() > MAX_EVIDENCE_ITEMS
                || report.conflictPairs().size() > MAX_EVIDENCE_ITEMS
                || report.resourceWindows().size() > MAX_EVIDENCE_ITEMS
                || report.violatedRules().size() > MAX_EVIDENCE_ITEMS;
        OffsetDateTime generatedAt = report.generatedAt() == null ? storedGeneratedAt : report.generatedAt();
        return new InfeasibilityReport(solveTaskId, report.verdict(),
                cap(report.blockingTasks()), cap(report.conflictPairs()),
                cap(report.resourceWindows()), cap(report.violatedRules()),
                report.assumptions(), generatedAt, solverRunId, report.candidateSeed(),
                report.truncated() || exceeded, report.narrative(), report.llmStatus());
    }

    private static <T> List<T> cap(List<T> values) {
        return values.size() <= MAX_EVIDENCE_ITEMS
                ? values : List.copyOf(values.subList(0, MAX_EVIDENCE_ITEMS));
    }

    private static InfeasibilityReport withComputedAssumption(InfeasibilityReport report) {
        List<String> assumptions = new ArrayList<>(report.assumptions());
        if (!assumptions.contains(COMPUTED_ONLY_ASSUMPTION)) {
            assumptions.add(COMPUTED_ONLY_ASSUMPTION);
        }
        return new InfeasibilityReport(report.solveTaskId(), report.verdict(),
                report.blockingTasks(), report.conflictPairs(), report.resourceWindows(),
                report.violatedRules(), assumptions, report.generatedAt(), report.solverRunId(),
                report.candidateSeed(), report.truncated(), report.narrative(), report.llmStatus());
    }

    /**
     * 从 solve_task.metrics 提取真实可得的求解运行标识；没有就返回 null，绝不编造。
     *
     * <p>兼容两种真实落点：顶层 {@code solverRunId}，或 portfolio 观测里的
     * {@code solverRunId}/{@code runId}。</p>
     */
    private static String solverRunId(String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = METRICS_MAPPER.readTree(metricsJson);
            String direct = text(root, "solverRunId");
            if (direct != null) {
                return direct;
            }
            JsonNode portfolio = root.get("portfolio");
            if (portfolio != null && portfolio.isObject()) {
                String runId = text(portfolio, "solverRunId");
                return runId != null ? runId : text(portfolio, "runId");
            }
            return null;
        } catch (JsonProcessingException exception) {
            LOGGER.warn("solve_task.metrics 不是合法 JSON，solverRunId 按 null 处理", exception);
            return null;
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(key);
        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText().trim();
    }
}
