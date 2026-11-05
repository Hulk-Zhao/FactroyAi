package com.hengce.aps.solver.api.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hengce.aps.solver.api.ScheduledTask;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 求解任务详情；{@code GET /solve-tasks/{id}} 的响应体，是进度展示的事实源。
 *
 * <p>序列化约定（C20）：声明的字段<strong>一律输出</strong>，{@code null} 表示“该阶段无此值”；
 * 键存在性不承载业务含义。</p>
 *
 * @param taskId 求解任务 ID
 * @param sandboxId 场景标识
 * @param strategyId 策略标识，可空
 * @param engine 引擎编码：TIMEFOLD/ORTOOLS/RULE_BASED
 * @param status 生命周期状态
 * @param progress 进度，0.0–1.0
 * @param phase 当前阶段：QUEUED/ASSEMBLING/SOLVING/PERSISTING/DONE/FAILED/CANCELLED/TIMEOUT
 * @param bestScore 最优评分文本，可空
 * @param scoreHard 硬分，可空
 * @param scoreSoft 软分，可空
 * @param elapsedMs 已耗时（毫秒）
 * @param resultTaskId 求解成功时的结果标识，可空
 * @param errorCode 失败/超时原因码，可空
 * @param errorMessage 失败/超时说明，可空
 * @param degraded 是否发生引擎降级（{@code metrics.engineFallback=true}）
 * @param metrics 求解指标（含 calendarFallback/dependencyFallback/setupMatrixFallback/
 *                engineFallback/engineFallbackReason/fallbackMode/fallbackAt 等）；
 *                Map 内的 {@code null} 值也必须输出（全局 {@code non_null} 不得作用于此字段内容）
 * @param startedAt 开始求解时间
 * @param finishedAt 结束时间，未结束为空
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param tasks 求解成功（SOLVED）时的结果任务快照；其余状态为空数组（C20）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SolveTaskDetail(
        long taskId,
        UUID sandboxId,
        UUID strategyId,
        String engine,
        String status,
        double progress,
        String phase,
        String bestScore,
        Long scoreHard,
        Long scoreSoft,
        long elapsedMs,
        Long resultTaskId,
        String errorCode,
        String errorMessage,
        boolean degraded,
        @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
        Map<String, Object> metrics,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ScheduledTask> tasks
) {

    /**
     * 归一化指标、状态与任务快照空值。
     *
     * <p>{@code metrics} 直接来自 JSONB，允许含 {@code null} 值（例如引擎未产出最佳分时的
     * {@code "bestScore": null}）。这里必须保留 null 语义：{@link Map#copyOf(Map)} 会因 null 值抛
     * {@link NullPointerException}，导致查询降级/失败任务详情 500，因此改用可容纳 null 的不可变副本。</p>
     */
    public SolveTaskDetail {
        metrics = metrics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        status = status == null ? SolveTaskStatus.PENDING.name() : status;
        engine = engine == null ? "" : engine;
        phase = phase == null ? "QUEUED" : phase;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
