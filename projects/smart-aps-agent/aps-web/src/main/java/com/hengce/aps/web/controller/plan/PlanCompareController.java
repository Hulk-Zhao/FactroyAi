package com.hengce.aps.web.controller.plan;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.plan.PlanCompareService;
import com.hengce.aps.service.plan.PlanCompareView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多策略择优对比接口（C35 §25.7.2）。
 *
 * <p>只读端点：不落库、不产生新计划版本；不新增批量求解端点（批量由客户端串行调用
 * {@code POST /solve-tasks}）。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/plans")
@Tag(name = "计划版本", description = "多策略择优对比（C35）")
public class PlanCompareController {

    private final PlanCompareService planCompareService;

    /**
     * 构造控制器。
     *
     * @param planCompareService 择优服务
     */
    public PlanCompareController(PlanCompareService planCompareService) {
        this.planCompareService = planCompareService;
    }

    /**
     * 对比 2–3 个计划版本。
     *
     * @param request 对比请求
     * @return 对比项（已排序）、推荐计划与并列规则
     */
    @Operation(summary = "计划版本择优对比",
            description = "planIds 去重后必须 2–3 个；确定性排序 SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC；"
                    + "metrics 透传 solve_task.metrics。")
    @RequiresPermission("SS_READ")
    @PostMapping("/compare")
    public Result<PlanCompareView> compare(@Valid @RequestBody PlanCompareRequest request) {
        return Result.success(planCompareService.compare(request.planIds()));
    }

    /**
     * 字面量优先：非 POST 方法命中 {@code /plans/compare} 时返回 405，不落入 {@code GET /plans/{id}}。
     *
     * @return 405 统一错误体
     */
    @RequestMapping(value = "/compare", method = {
            RequestMethod.GET, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<Result<Void>> compareMethodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.failure(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 对比请求体：{@code {"planIds": ["...", "..."]}}。
     *
     * @param planIds 计划版本主键
     */
    public record PlanCompareRequest(@NotNull List<UUID> planIds) {
    }
}
