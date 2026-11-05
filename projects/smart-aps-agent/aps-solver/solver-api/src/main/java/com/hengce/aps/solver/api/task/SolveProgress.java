package com.hengce.aps.solver.api.task;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * STOMP 求解进度消息（contract-freeze C20 §8.3 消息体）。
 *
 * <p>订阅 {@code /topic/solve-tasks/{taskId}} 后立即收到一条当前快照，随后按阶段推送。
 * 消息只用于展示，事实源始终是 {@code GET /solve-tasks/{taskId}}；断线后前端回退 2 秒轮询。</p>
 *
 * <p>序列化约定（C20）：声明的字段<strong>一律输出</strong>，{@code null} 表示“该阶段无此值”；
 * 键存在性不承载业务含义。</p>
 *
 * @param taskId 求解任务 ID，对应 {@code solve_task.id}
 * @param status 状态：PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT
 * @param progress 进度，0.0–1.0
 * @param phase 阶段：QUEUED/ASSEMBLING/SOLVING/PERSISTING/DONE/FAILED/CANCELLED/TIMEOUT
 * @param bestScore 当前最优评分文本，未知时为 {@code null}
 * @param elapsedMs 已耗时（毫秒）
 * @param resultTaskId 求解成功时的结果标识（M2 返回 solve_task ID），其余状态为 {@code null}
 * @param errorCode 失败/超时原因码，成功时为 {@code null}
 * @param message 面向用户的说明
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SolveProgress(
        long taskId,
        String status,
        double progress,
        String phase,
        String bestScore,
        long elapsedMs,
        Long resultTaskId,
        String errorCode,
        String message
) {

    /**
     * 归一化进度区间与空值。
     */
    public SolveProgress {
        double normalized = progress < 0.0 ? 0.0 : Math.min(progress, 1.0);
        progress = normalized;
        status = status == null ? SolveTaskStatus.PENDING.name() : status;
        phase = phase == null ? "QUEUED" : phase;
    }
}
