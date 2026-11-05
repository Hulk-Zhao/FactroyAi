package com.hengce.aps.web.controller.contract;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.schedule.atp.AtpQuery;
import com.hengce.aps.service.schedule.atp.AtpResult;
import com.hengce.aps.service.schedule.atp.AtpService;
import com.hengce.aps.service.schedule.ctp.CtpInquiryService;
import com.hengce.aps.solver.api.ctp.CtpRequest;
import com.hengce.aps.solver.api.ctp.CtpResult;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单与场景域契约端点（orders 分组）。
 *
 * <p>GET /orders、GET /orders/{orderNo}、POST /orders、PUT /orders/{orderNo}、
 * PUT /orders/{orderNo}/confirm、PUT /orders/{orderNo}/freeze、PUT /orders/{orderNo}/start
 * 已由 {@code OrderController} 实现；`/strategies/**` 由 {@code StrategyController} 实现（C19）；
 * `/sandboxes` 由 {@code SandboxController} 实现（C32）。本类只承载 ATP 交期预估（C46），
 * 字段契约见 {@code docs/api/openapi/orders.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "订单与场景", description = "ATP 交期预估")
public class OrdersContractController {

    private final AtpService atpService;

    private final CtpInquiryService ctpInquiryService;

    /**
     * 构造控制器。
     *
     * @param atpService ATP 服务
     * @param ctpInquiryService CTP 物料侧询单服务
     */
    public OrdersContractController(AtpService atpService, CtpInquiryService ctpInquiryService) {
        this.atpService = atpService;
        this.ctpInquiryService = ctpInquiryService;
    }

    /**
     * ATP 交期预估（FAST 估算 / ACCURATE 限时求解），只读，不落主计划。
     *
     * @param body 预估参数
     * @return 预估结果
     */
    @Operation(summary = "ATP 交期预估",
            description = "FAST 构造启发式为 ESTIMATE；ACCURATE 限时求解，超时不伪造日期。"
                    + "结果只读，不写 task/plan_version/orders，产能锁定仅返回建议。")
    @RequiresPermission("OM_ATP")
    @PostMapping("/orders/atp")
    public Result<AtpResult> atp(@RequestBody(required = false) AtpQuery body) {
        return Result.success(atpService.estimate(body));
    }

    /**
     * CTP 交期承诺询单（C53）。
     *
     * <p>同步只读；当前实现只做物料可齐日判定（库存 + 在途 + 替代 + 多级 BOM），
     * 容量未验证时返回 {@code UNVERIFIED}，绝不返回承诺日期。</p>
     *
     * @param body 询单参数
     * @return 询单结果
     */
    @Operation(summary = "CTP 交期承诺询单（物料侧）",
            description = "物料可齐日判定：库存 + 需求日前在途 + 替代料 + 多级 BOM。"
                    + "容量未验证返回 UNVERIFIED，不返回承诺日期；只读，不写 plan_version/task/orders/solve_task。")
    @RequiresPermission("OM_ATP")
    @PostMapping("/orders/ctp")
    public Result<CtpResult> ctp(@RequestBody(required = false) CtpRequest body) {
        return Result.success(ctpInquiryService.inquire(body));
    }
}
