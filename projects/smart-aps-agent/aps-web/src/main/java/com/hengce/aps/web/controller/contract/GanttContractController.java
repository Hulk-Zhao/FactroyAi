package com.hengce.aps.web.controller.contract;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.schedule.TaskAdjustService;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 甘特图与计划调整域契约（gantt 分组，C22）。
 *
 * <p>字段契约见 {@code docs/api/openapi/gantt.yaml}。本控制器是
 * {@code /tasks/{id}/adjust} 及其撤销/重做/历史的唯一映射，禁止在其它控制器重复注册同路径。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "甘特图与计划", description = "甘特图、人工调整、计划发布/回滚")
public class GanttContractController {

    private final TaskAdjustService taskAdjustService;

    /**
     * 构造控制器。
     *
     * @param taskAdjustService 人工调整服务
     */
    public GanttContractController(TaskAdjustService taskAdjustService) {
        this.taskAdjustService = taskAdjustService;
    }

    /**
     * 人工调整排产任务。
     *
     * @param id 排产任务 ID
     * @param body 调整内容
     * @param idempotencyKey 幂等键（必填，缺失 40000）
     * @return 调整结果；dryRun 时不落库
     */
    @Operation(summary = "人工调整任务",
            description = "状态：已实现（C22/C36）。MOVE/LOCK/FREEZE/UNFREEZE/SPLIT/MERGE。"
                    + "SPLIT 需 splitQuantities、MERGE 需 mergeTaskIds，均必填 reason 与 Idempotency-Key；"
                    + "version 过期 40901，不可行 42201，参数非法 42200，已执行任务 40903。")
    @RequiresPermission("SCHEDULE_ADJUST")
    @PostMapping("/tasks/{id}/adjust")
    public Result<TaskAdjustService.TaskAdjustResult> adjustTask(
            @PathVariable Long id,
            @RequestBody(required = false) TaskAdjustService.TaskAdjustCommand body,
            @RequestHeader(value = ApsConstants.HEADER_IDEMPOTENCY_KEY, required = false)
            String idempotencyKey) {
        return Result.success(taskAdjustService.adjust(id, body, idempotencyKey));
    }

    /**
     * 撤销最近一次人工调整。
     *
     * @param adjustmentId 调整记录 ID
     * @return 撤销后的任务视图
     */
    @Operation(summary = "撤销人工调整", description = "状态：已实现（C22）。只允许撤销任务栈上最近一次调整。")
    @RequiresPermission("SCHEDULE_ADJUST")
    @PostMapping("/tasks/adjustments/{adjustmentId}/undo")
    public Result<TaskAdjustService.TaskAdjustResult> undoAdjustment(@PathVariable Long adjustmentId) {
        return Result.success(taskAdjustService.undo(adjustmentId));
    }

    /**
     * 重做已撤销的人工调整。
     *
     * @param adjustmentId 调整记录 ID
     * @return 重做后的任务视图
     */
    @Operation(summary = "重做人工调整", description = "状态：已实现（C22）。若该调整之后已有新调整则 40901。")
    @RequiresPermission("SCHEDULE_ADJUST")
    @PostMapping("/tasks/adjustments/{adjustmentId}/redo")
    public Result<TaskAdjustService.TaskAdjustResult> redoAdjustment(@PathVariable Long adjustmentId) {
        return Result.success(taskAdjustService.redo(adjustmentId));
    }

    /**
     * 查询任务人工调整历史。
     *
     * @param id 排产任务 ID
     * @param page 页码，1 起始
     * @param size 页大小
     * @return 调整历史分页
     */
    @Operation(summary = "任务调整历史", description = "状态：已实现（C22）。")
    @GetMapping("/tasks/{id}/adjustments")
    public Result<TaskAdjustService.AdjustmentPage> adjustmentHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(taskAdjustService.history(id, page, size));
    }
}
