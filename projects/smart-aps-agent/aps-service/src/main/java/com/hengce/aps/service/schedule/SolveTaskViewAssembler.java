package com.hengce.aps.service.schedule;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.solver.api.ScheduledTask;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 求解任务对外视图装配（C20）。
 *
 * <p>终态 {@code SOLVED} 时把场景当前任务快照填入 {@link SolveTaskDetail#tasks()}：
 * {@code POST /solve-tasks}、{@code GET /solve-tasks/{id}}、取消与增量接口共用同一口径。
 * 其余状态返回空数组，不透传任何领域状态改写（DB 与 API 同用六值枚举）。</p>
 */
@Service
public class SolveTaskViewAssembler {

    /**
     * 成功终态。
     */
    private static final String SOLVED = "SOLVED";

    private final ScheduleRepository scheduleRepository;

    /**
     * 构造装配器。
     *
     * @param scheduleRepository 排产持久层
     */
    public SolveTaskViewAssembler(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * 按状态补充任务快照。
     *
     * @param detail 生命周期详情
     * @return 对外详情；非 SOLVED 原样返回
     */
    public SolveTaskDetail withTasks(SolveTaskDetail detail) {
        if (detail == null || !SOLVED.equals(detail.status())) {
            return detail;
        }
        List<ScheduledTask> tasks = scheduleRepository
                .findScheduledTaskRows(TenantContext.getTenantId(), detail.sandboxId())
                .stream()
                .map(row -> new ScheduledTask(row.orderNo(), row.resourceCode(),
                        row.startTime(), row.endTime()))
                .toList();
        return new SolveTaskDetail(detail.taskId(), detail.sandboxId(), detail.strategyId(),
                detail.engine(), detail.status(), detail.progress(), detail.phase(),
                detail.bestScore(), detail.scoreHard(), detail.scoreSoft(), detail.elapsedMs(),
                detail.resultTaskId(), detail.errorCode(), detail.errorMessage(), detail.degraded(),
                detail.metrics(), detail.startedAt(), detail.finishedAt(), detail.createdAt(),
                detail.updatedAt(), tasks);
    }
}
