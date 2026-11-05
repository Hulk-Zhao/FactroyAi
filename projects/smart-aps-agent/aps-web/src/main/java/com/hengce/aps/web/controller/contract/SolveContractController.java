package com.hengce.aps.web.controller.contract;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.agent.AgentEventRequest;
import com.hengce.aps.service.agent.AgentEventService;
import com.hengce.aps.service.agent.AgentEventView;
import com.hengce.aps.service.agent.AgentExplainService;
import com.hengce.aps.service.agent.AgentExplainView;
import com.hengce.aps.service.agent.AgentWhatIfRequest;
import com.hengce.aps.service.agent.AgentWhatIfService;
import com.hengce.aps.service.agent.AgentWhatIfView;
import com.hengce.aps.solver.api.counterfactual.CounterfactualRequest;
import com.hengce.aps.solver.api.counterfactual.CounterfactualResult;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.service.schedule.infeasibility.InfeasibilityReportService;
import com.hengce.aps.service.schedule.verification.CounterfactualService;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 域契约端点（solve 分组）。
 *
 * <p>确定性能力优先：explain / whatif / events / nl-to-dsl 均不调用任何外部模型；
 * 不支持的语义保持诚实拒绝，不返回假数据。契约状态见 {@code docs/api/openapi/solve.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "求解与 Agent", description = "求解任务、Agent（确定性能力优先）")
public class SolveContractController {

    private final AgentEventService agentEventService;

    private final AgentExplainService agentExplainService;

    private final AgentWhatIfService agentWhatIfService;

    private final InfeasibilityReportService infeasibilityReportService;

    private final CounterfactualService counterfactualService;

    /**
     * 构造控制器。
     *
     * @param agentEventService 事件上报服务
     * @param agentExplainService 归因解释服务
     * @param agentWhatIfService what-if 服务
     * @param infeasibilityReportService C51 不可行证据服务
     * @param counterfactualService C52 反事实验证服务
     */
    public SolveContractController(AgentEventService agentEventService,
                                   AgentExplainService agentExplainService,
                                   AgentWhatIfService agentWhatIfService,
                                   InfeasibilityReportService infeasibilityReportService,
                                   CounterfactualService counterfactualService) {
        this.agentEventService = agentEventService;
        this.agentExplainService = agentExplainService;
        this.agentWhatIfService = agentWhatIfService;
        this.infeasibilityReportService = infeasibilityReportService;
        this.counterfactualService = counterfactualService;
    }

    /**
     * 上报异常事件（幂等落在 audit_log，重复键不新增行）。
     *
     * @param body 事件字段
     * @param idempotencyKey 事件标识/幂等键
     * @return 审计证据
     */
    @Operation(summary = "上报异常事件",
            description = "状态：已实现。事件落 audit_log（动作 AGENT_EVENT_REPORTED）；"
                    + "Idempotency-Key 必填且为事件标识，重复上报不新增行；"
                    + "userId/tenantId 由认证上下文提供，请求体指定返回 40000。")
    @RequiresPermission("AGENT_EVENT")
    @PostMapping("/agent/events")
    public Result<AgentEventView> reportEvent(
            @RequestBody(required = false) AgentEventRequest body,
            @RequestHeader(value = ApsConstants.HEADER_IDEMPOTENCY_KEY, required = false)
            String idempotencyKey) {
        return Result.success(agentEventService.report(body, idempotencyKey));
    }

    /**
     * 自然语言转 DSL —— 保持 501。
     *
     * <p>阻塞点：{@code constraint_dsl.compiled_spec} 只存储/激活，
     * {@code ScheduleProblemAssembler} 传给求解器的 {@code constraints} 仍是空列表
     * （{@code ScheduleProblemAssembler.java} 第 299 行 {@code List.of()}）。
     * DSL 不影响排产前做 NL 翻译就是假功能，因此保持 501。</p>
     *
     * @param body 忽略
     * @return 不返回
     */
    @Operation(summary = "自然语言转 DSL（未实现）",
            description = "状态：placeholder（50100）。阻塞于 DSL→求解接线："
                    + "constraint_dsl.compiled_spec 未进入 ScheduleProblem.constraints，"
                    + "此时生成 DSL 不影响排产，属于假功能；接线完成并通过 42201–42207 校验后才开卡。")
    @RequiresPermission("AGENT_EVENT")
    @PostMapping("/agent/nl-to-dsl")
    public Result<Object> nlToDsl(@RequestBody(required = false) Object body) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 归因解释（真实 constraint_justification 证据）。
     *
     * @param taskId 排产任务主键（task.id），经 implicated_facts 反查 solve_task
     * @return 解释视图；映射不唯一/无归因时 available=false
     */
    @Operation(summary = "归因解释",
            description = "状态：已实现。taskId 为排产任务主键（task.id），归因按 solve_task 持久化，"
                    + "服务端用 implicated_facts 反查；0 条返回 available=false +"
                    + " NO_JUSTIFICATION_RECORD，命中多个 solve_task 返回 available=false +"
                    + " AMBIGUOUS_SOLVE_TASK + 候选，禁止猜测。")
    @GetMapping("/agent/explain/{taskId}")
    public Result<AgentExplainView> explain(@PathVariable Long taskId) {
        return Result.success(agentExplainService.explain(taskId));
    }

    /**
     * 对话式 what-if（真实求解 + 既有沙箱 compare）。
     *
     * @param body 对话参数
     * @param idempotencyKey 求解幂等键
     * @return 方案对比
     */
    @Operation(summary = "对话式 what-if",
            description = "状态：已实现。只在 WHAT_IF 场景内同步真实求解，随后复用沙箱 compare 返回"
                    + "前/后差异、影响订单与瓶颈变化；BASELINE/ARCHIVED 场景与未知参数返回 42200。")
    @RequiresPermission("AGENT_EVENT")
    @PostMapping("/agent/whatif")
    public Result<AgentWhatIfView> whatIf(
            @RequestBody(required = false) AgentWhatIfRequest body,
            @RequestHeader(value = ApsConstants.HEADER_IDEMPOTENCY_KEY, required = false)
            String idempotencyKey) {
        return Result.success(agentWhatIfService.run(body, idempotencyKey));
    }

    /**
     * 求解不可行证据（C51）。
     *
     * <p>先读 {@code infeasibility_report} 落库行；未命中时按 {@code solve_task.config}
     * 现算并在 assumptions 标明“未落库”。失败/超时任务也可查，无证据返回 200 + NO_EVIDENCE。</p>
     *
     * @param id 求解任务主键
     * @return C51 报告
     */
    @Operation(summary = "求解不可行证据",
            description = "状态：已实现。先查落库报告，未命中按 solve_task.config 现算；"
                    + "每类证据 <= 8 条，超限置 truncated；NO_EVIDENCE 的 generatedAt/narrative 为 null。")
    @RequiresPermission("SE_SOLVE")
    @GetMapping("/solve-tasks/{id}/infeasibility")
    public Result<InfeasibilityReport> infeasibility(@PathVariable Long id) {
        return Result.success(infeasibilityReportService.report(TenantContext.getTenantId(), id));
    }

    /**
     * 反事实修复验证（C52）。
     *
     * <p>按 {@code solve_task.config} 重放题面并做最小改动，再走不落库验证求解；
     * 只读，不写 {@code plan_version}/{@code task}，{@code mainPlanWritten} 恒 false。</p>
     *
     * @param id 求解任务主键
     * @param body 修复类型与参数
     * @return 验证结论；仅 VERIFIED_FEASIBLE 带 changes
     */
    @Operation(summary = "反事实修复验证",
            description = "状态：已实现。UNFREEZE_TASKS/EXTEND_CALENDAR 应用最小改动后只读重解；"
                    + "其余 kind 在数据不足时返回 UNVERIFIED；预算耗尽返回 OUT_OF_BUDGET，"
                    + "不判不可行；STILL_INFEASIBLE 只给证据不给建议日期。")
    @RequiresPermission("SE_SOLVE")
    @PostMapping("/solve-tasks/{id}/counterfactual")
    public Result<CounterfactualResult> counterfactual(
            @PathVariable Long id,
            @RequestBody(required = false) CounterfactualRequest body) {
        return Result.success(counterfactualService.verify(id, body));
    }
}
