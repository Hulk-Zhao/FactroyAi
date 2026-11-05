package com.hengce.aps.web.controller;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.schedule.IncrementalSolveService;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.service.schedule.SolveTaskViewAssembler;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求解任务 API（M2-SE-03 异步生命周期）。
 *
 * <p>{@code POST /solve-tasks} 一律返回 {@link SolveTaskDetail}（C20）；异步开关只影响
 * 执行方式（后台线程或同步阻塞），不影响响应结构。进度通过 STOMP
 * {@code /topic/solve-tasks/{id}} 推送，事实源为 {@code GET /solve-tasks/{id}}。
 * 归因查询用于 M2-SE-05 归因重放。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/solve-tasks")
@Tag(name = "求解与 Agent", description = "求解任务生命周期、归因与取消")
public class SolveTaskController {

    /**
     * 生命周期服务。
     */
    private final SolveTaskLifecycleService lifecycleService;

    /**
     * 增量重排服务。
     */
    private final IncrementalSolveService incrementalSolveService;

    /**
     * 任务视图装配（终态填入 tasks 快照）。
     */
    private final SolveTaskViewAssembler viewAssembler;

    /**
     * 构造控制器。
     *
     * @param lifecycleService 生命周期服务
     * @param incrementalSolveService 增量重排服务
     * @param viewAssembler 任务视图装配器
     */
    public SolveTaskController(SolveTaskLifecycleService lifecycleService,
                               IncrementalSolveService incrementalSolveService,
                               SolveTaskViewAssembler viewAssembler) {
        this.lifecycleService = lifecycleService;
        this.incrementalSolveService = incrementalSolveService;
        this.viewAssembler = viewAssembler;
    }

    /**
     * 提交增量重排（C21 §24.3）。
     *
     * @param request 增量重排请求
     * @param idempotencyKey 幂等键，可空
     * @return 新 planId 与求解任务详情
     */
    @Operation(summary = "增量重排",
            description = "复用基准解配置，写新 solve_task 与新 DRAFT plan_version；"
                    + "场景内含已执行任务 40903，齐套未通过 42200")
    @RequiresPermission("SE_SOLVE")
    @PostMapping("/incremental")
    public Result<IncrementalSolveService.IncrementalSolveResult> incremental(
            @RequestBody IncrementalSolveService.IncrementalSolveRequest request,
            @RequestHeader(value = ApsConstants.HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return Result.success(incrementalSolveService.submit(request, idempotencyKey));
    }

    /**
     * 提交求解任务；同一 {@code Idempotency-Key} 返回既有任务。
     *
     * <p>同步执行（默认）时返回终态详情（含 `tasks`）；异步开启时返回 PENDING/RUNNING，
     * 调用方轮询 {@code GET /solve-tasks/{id}} 获取终态与任务快照。</p>
     *
     * @param request 求解请求
     * @param idempotencyKey 幂等键，可空
     * @return 任务详情
     */
    @Operation(summary = "提交求解任务",
            description = "一律返回 SolveTaskDetail（C20）；状态使用六值枚举，终态任务快照见 tasks")
    @RequiresPermission("SE_SOLVE")
    @PostMapping
    public Result<SolveTaskDetail> submit(
            @RequestBody SolveRequest request,
            @RequestHeader(value = ApsConstants.HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return Result.success(viewAssembler.withTasks(lifecycleService.submit(request, idempotencyKey)));
    }

    /**
     * 查询求解任务详情（进度事实源）。
     *
     * @param id 求解任务 ID
     * @return 任务详情
     */
    @Operation(summary = "求解任务详情", description = "状态、进度、评分、降级指标与终态任务快照")
    @GetMapping("/{id}")
    public Result<SolveTaskDetail> detail(@PathVariable long id) {
        return Result.success(viewAssembler.withTasks(lifecycleService.detail(id)));
    }

    /**
     * 取消求解任务。
     *
     * @param id 求解任务 ID
     * @return 取消后的任务详情
     */
    @Operation(summary = "取消求解任务", description = "已终态返回 40901；重复取消幂等")
    @RequiresPermission("SE_SOLVE")
    @PostMapping("/{id}/cancel")
    public Result<SolveTaskDetail> cancel(@PathVariable long id) {
        return Result.success(lifecycleService.cancel(id));
    }

    /**
     * 查询求解任务的约束归因（M2-SE-05 归因重放）。
     *
     * @param id 求解任务 ID
     * @return 归因列表
     */
    @Operation(summary = "求解任务归因", description = "读取 constraint_justification 并重放 task_ids")
    @GetMapping("/{id}/justifications")
    public Result<List<ConstraintJustification>> justifications(@PathVariable long id) {
        List<ConstraintJustification> body = lifecycleService.justifications(id).stream()
                .map(row -> new ConstraintJustification(row.constraintId(),
                        row.scoreImpact() == null ? "HARD" : row.scoreImpact(),
                        row.taskIds(), row.description(), row.weight(), row.deduction()))
                .toList();
        return Result.success(body);
    }
}
