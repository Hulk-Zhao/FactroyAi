package com.hengce.aps.service.schedule.infeasibility;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.service.schedule.SolveFinishEvent;
import com.hengce.aps.service.schedule.SolvePostProcessor;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 不可行归因后置处理器（B1）。
 *
 * <p>实现 A0 的 {@link SolvePostProcessor} 接缝：终态后按 {@code solveTaskId} 重放题面、
 * 采集证据、判定 verdict，放入进程内缓存并按 {@code (tenant, solve_task_id)} 幂等落库。</p>
 *
 * <p>租户取终态事件字段（终态线程的 ThreadLocal 可能已被清理），不依赖 ThreadLocal。
 * 落库失败只记日志，不影响求解终态；其它异常由 {@code SolvePostProcessorChain} 隔离。</p>
 */
@Component
@Order(100)
public class InfeasibilityPostProcessor implements SolvePostProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(InfeasibilityPostProcessor.class);

    /**
     * 进程内缓存上限。
     */
    private static final int CACHE_LIMIT = 256;

    private final InfeasibilityAnalyzer analyzer;

    private final InfeasibilityReportService reportService;

    private final Map<Long, InfeasibilityAnalysis> cache = new LinkedHashMap<>();

    /**
     * 构造后置处理器。
     *
     * @param analyzer 归因分析器
     * @param reportService 归因落库服务
     */
    public InfeasibilityPostProcessor(InfeasibilityAnalyzer analyzer,
                                      InfeasibilityReportService reportService) {
        this.analyzer = analyzer;
        this.reportService = reportService;
    }

    /**
     * 求解终态回调。
     *
     * @param event 终态事件
     */
    @Override
    public void onSolveFinished(SolveFinishEvent event) {
        InfeasibilityAnalysis analysis = analyzer.analyze(event);
        remember(event.taskId(), analysis);
        persist(event, analysis);
        InfeasibilityReport report = analysis.report();
        LOGGER.info("不可行归因: taskId={}, verdict={}, blocking={}, conflicts={}, windows={}, rules={},"
                        + " truncated={}, llmStatus={}",
                event.taskId(), report.verdict(), report.blockingTasks().size(),
                report.conflictPairs().size(), report.resourceWindows().size(),
                report.violatedRules().size(), report.truncated(), report.llmStatus());
    }

    /**
     * 读取最近一次归因结果（供 B2 落库或调试）。
     *
     * @param solveTaskId 求解任务主键
     * @return 归因结果
     */
    public Optional<InfeasibilityAnalysis> cached(long solveTaskId) {
        synchronized (cache) {
            return Optional.ofNullable(cache.get(solveTaskId));
        }
    }

    /**
     * 按任务主键现场分析（B2 可直接调用）。
     *
     * @param tenantId 租户标识
     * @param solveTaskId 求解任务主键
     * @return 归因结果
     */
    public InfeasibilityAnalysis analyze(UUID tenantId, long solveTaskId) {
        InfeasibilityAnalysis analysis = analyzer.analyzeTask(tenantId, solveTaskId);
        remember(solveTaskId, analysis);
        return analysis;
    }

    private void persist(SolveFinishEvent event, InfeasibilityAnalysis analysis) {
        UUID tenantId = event.tenantId() == null ? TenantContext.getTenantId() : event.tenantId();
        try {
            reportService.persist(tenantId, event.taskId(), analysis);
        } catch (RuntimeException exception) {
            LOGGER.warn("不可行归因落库失败（不影响求解终态）: taskId={}, tenantId={}",
                    event.taskId(), tenantId, exception);
        }
    }

    private void remember(long solveTaskId, InfeasibilityAnalysis analysis) {
        synchronized (cache) {
            cache.put(solveTaskId, analysis);
            while (cache.size() > CACHE_LIMIT) {
                Iterator<Long> iterator = cache.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }
}
