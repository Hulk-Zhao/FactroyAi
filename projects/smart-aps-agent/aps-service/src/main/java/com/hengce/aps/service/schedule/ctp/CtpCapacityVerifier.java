package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategory;
import com.hengce.aps.infrastructure.persistence.material.MaterialCategoryMapper;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpOrderSpec;
import com.hengce.aps.service.schedule.ScheduleProblemAssembler.AtpSnapshot;
import com.hengce.aps.service.schedule.verification.VerificationOutcome;
import com.hengce.aps.service.schedule.verification.VerificationSolver;
import com.hengce.aps.service.schedule.verification.VerificationStatus;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.ctp.CtpResult;
import com.hengce.aps.solver.api.ctp.CtpVerdict;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * C53 容量验证（在物料侧 UNVERIFIED 之后继续）。
 *
 * <p>用 {@link VerificationSolver} 在 {@code now+168h} 内排该订单，严格接受三条：
 * {@code hard == 0}、该订单全部工序已分配、完成时间 ≤ {@code requestedDate}。
 * 任一不满足绝不承诺。</p>
 *
 * <p>物料可齐日作为求解下界；物料可齐日晚于请求交期时直接 {@code UNPROMISABLE}，不求解。</p>
 *
 * <p>只读：不写 {@code plan_version}/{@code task}/{@code orders}/{@code solve_task}，
 * {@code mainPlanWritten} 恒 false。</p>
 */
@Service
public class CtpCapacityVerifier {

    /** 默认视野小时数（C53：168h）。 */
    private static final int DEFAULT_HORIZON_HOURS = 168;

    /**
     * 与 ATP ACCURATE 一致的软约束权重：CTP 只关心最早可交付，MIN_MAKESPAN 加权到 20。
     */
    private static final Map<String, Integer> ATP_SOFT_WEIGHTS = Map.of(
            "MIN_TARDINESS", 20,
            "EDD", 10,
            "SPT", 10,
            "SAME_PRODUCT_CONTINUOUS", 10,
            "MIN_SETUP_TIME", 10,
            "BALANCE_LOAD", 10,
            "PRIORITY_RESPECT", 10,
            "MIN_MAKESPAN", 20);

    private final ScheduleProblemAssembler assembler;

    private final VerificationSolver verificationSolver;

    private final MaterialCategoryMapper materialCategoryMapper;

    private final ZoneId businessZone;

    private final int horizonHours;

    /**
     * 构造容量验证器。
     *
     * @param assembler 题面装配器（只读）
     * @param verificationSolver 不落库验证求解接缝
     * @param materialCategoryMapper 物料分类 Mapper（产品族）
     * @param businessTimeZone 业务时区
     * @param horizonHours 视野小时数（168）
     */
    public CtpCapacityVerifier(ScheduleProblemAssembler assembler,
                               VerificationSolver verificationSolver,
                               MaterialCategoryMapper materialCategoryMapper,
                               @Value("${aps.business.time-zone:Asia/Shanghai}") String businessTimeZone,
                               @Value("${aps.ctp.horizon-hours:168}") int horizonHours) {
        this.assembler = assembler;
        this.verificationSolver = verificationSolver;
        this.materialCategoryMapper = materialCategoryMapper;
        this.businessZone = ZoneId.of(businessTimeZone.trim());
        this.horizonHours = horizonHours <= 0 ? DEFAULT_HORIZON_HOURS : horizonHours;
    }

    /**
     * 对已通过物料门的订单做容量验证。
     *
     * @param material 产品物料
     * @param normalized 归一化询单参数
     * @param materialReadyAt 物料最早可齐日
     * @param priority 优先级
     * @param now 本次询单基准时刻（UTC）
     * @return 容量验证结论；仅 PROMISED 带承诺日期/数量/验证时间
     */
    public CapacityOutcome verify(Material material,
                                  CtpRequestValidator.NormalizedCtpRequest normalized,
                                  LocalDate materialReadyAt, String priority, OffsetDateTime now) {
        List<String> assumptions = new ArrayList<>();
        assumptions.add("CAPACITY_LOWER_BOUND_MATERIAL_READY：求解下界为物料可齐日 "
                + materialReadyAt + " 00:00（业务时区）。");
        assumptions.add("HORIZON_168H：容量验证视野为基准时刻 + " + horizonHours + " 小时。");
        assumptions.add("NO_RECURSIVE_MAKE：CTP 只排该订单自身工序，不递归展开半成品。");
        assumptions.add("NOT_PERSISTED：容量验证只读，不写 plan_version/task/orders/solve_task。");
        if (materialReadyAt == null || materialReadyAt.isAfter(normalized.requestedDate())) {
            return unpromisable("物料可齐日 " + materialReadyAt + " 晚于请求交期 "
                    + normalized.requestedDate() + "，未做容量求解。", assumptions, now);
        }
        AtpOrderSpec spec = new AtpOrderSpec(UUID.randomUUID(),
                "CTP-" + UUID.randomUUID().toString().substring(0, 8), material.getCode(),
                productFamily(material), normalized.qty(), normalized.requestedDate(),
                priority == null ? "NORMAL" : priority, null);
        AtpSnapshot snapshot;
        try {
            snapshot = assembler.assembleAtp(spec, horizonHours, true);
        } catch (RuntimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("工艺路线")) {
                return noRoute("物料 " + material.getCode() + " 没有 ACTIVE 工艺路线，无法验证产能。",
                        assumptions, now);
            }
            return unpromisable("容量验证题面装配失败：" + exception.getMessage(), assumptions, now);
        }
        if (snapshot.atpOrder() == null || snapshot.problem().resources().isEmpty()) {
            return unpromisable("当前场景没有可用资源，无法验证产能。", assumptions, now);
        }
        ScheduleProblem problem = withMaterialLowerBound(snapshot.problem(),
                snapshot.atpOrder(), materialReadyAt);
        int unimprovedSeconds = Math.min(2, Math.max(1, normalized.timeoutSeconds() - 1));
        SolveConfig config = new SolveConfig("timefold", normalized.timeoutSeconds(), 0,
                "LATE_ACCEPTANCE", 0L, 0L, unimprovedSeconds, "FORWARD", true,
                Map.of("softWeights", ATP_SOFT_WEIGHTS));
        VerificationOutcome outcome = verificationSolver.solve(problem, config,
                Set.of(snapshot.atpOrder().orderId()));
        CtpResult.CtpSolverInfo solver = new CtpResult.CtpSolverInfo(outcome.engine(),
                outcome.seed(), outcome.hardScore(), outcome.softScore(), outcome.durationMs(),
                outcome.terminationReason(), null);
        if (outcome.status() == VerificationStatus.UNVERIFIED) {
            return solverFailure("求解器未完成容量验证：" + outcome.message(), solver,
                    assumptions, now);
        }
        if (outcome.status() == VerificationStatus.OUT_OF_BUDGET) {
            return solverFailure("容量验证在 " + normalized.timeoutSeconds() + " 秒预算内未完成；"
                    + "预算耗尽不等于不可行，不能承诺。", solver, assumptions, now);
        }
        if (outcome.status() == VerificationStatus.INFEASIBLE) {
            return unpromisable("容量验证不可行：硬分 "
                    + (outcome.hardScore() == null ? "-" : outcome.hardScore().toPlainString())
                    + "，已分配 " + outcome.assignedTaskCount() + "/" + outcome.totalTaskCount()
                    + " 道工序。", solver, assumptions, now);
        }
        TaskAssignment last = lastAssignment(outcome.assignments(), snapshot.atpOrder().orderId());
        if (last == null || last.endTime() == null) {
            return unpromisable("容量验证可行但没有返回该订单的完整排布，不能承诺。",
                    solver, assumptions, now);
        }
        LocalDate completionDate = last.endTime().atZoneSameInstant(businessZone).toLocalDate();
        if (completionDate.isAfter(normalized.requestedDate())) {
            return unpromisable("容量验证完成日 " + completionDate + " 晚于请求交期 "
                    + normalized.requestedDate() + "，不能承诺。", solver, assumptions, now,
                    completionDate);
        }
        Map<String, Object> evidence = evidence("PROMISED", last.resourceCode(),
                firstStart(outcome.assignments(), snapshot.atpOrder().orderId()), last.endTime(),
                "容量验证通过：hard=0，工序全部已分配，完成 " + completionDate
                        + " ≤ 请求交期 " + normalized.requestedDate() + "。",
                completionDate);
        return new CapacityOutcome(CtpVerdict.PROMISED, completionDate, normalized.qty(),
                OffsetDateTime.now(ZoneOffset.UTC).withNano(0), solver, evidence, assumptions,
                "容量验证通过，可在 " + completionDate
                        + " 承诺 " + normalized.qty().stripTrailingZeros().toPlainString()
                        + "（" + material.getCode() + "）。");
    }

    private ScheduleProblem withMaterialLowerBound(ScheduleProblem problem,
                                                   SchedulingOrder atpOrder,
                                                   LocalDate materialReadyAt) {
        LocalDateTime lowerBound = materialReadyAt.atStartOfDay(businessZone)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        SchedulingOrder bounded = new SchedulingOrder(atpOrder.orderId(), atpOrder.orderNo(),
                atpOrder.materialCode(), atpOrder.productFamily(), atpOrder.qty(),
                atpOrder.dueDate(), atpOrder.priority(), atpOrder.freezeStatus(),
                atpOrder.routingId(), atpOrder.operations(), lowerBound);
        List<SchedulingOrder> orders = new ArrayList<>(problem.orders().size());
        for (SchedulingOrder order : problem.orders()) {
            orders.add(order.orderId().equals(atpOrder.orderId()) ? bounded : order);
        }
        return new ScheduleProblem(problem.tenantId(), problem.sandboxId(), problem.solveTaskId(),
                problem.strategyId(), problem.direction(), List.copyOf(orders),
                problem.resources(), problem.frozenAssignments(), problem.constraints(),
                problem.setupMatrix(), problem.baseTime(), problem.initialAssignments());
    }

    private String productFamily(Material material) {
        if (material.getCategoryId() == null) {
            return null;
        }
        MaterialCategory category = materialCategoryMapper.selectById(material.getCategoryId());
        return category == null ? null : category.getCode();
    }

    private CapacityOutcome noRoute(String message, List<String> assumptions,
                                    OffsetDateTime now) {
        Map<String, Object> evidence = evidence("NO_ROUTE", null, null, null, message, null);
        return new CapacityOutcome(CtpVerdict.NO_ROUTE, null, null, null, null, evidence,
                assumptions, message);
    }

    private CapacityOutcome unpromisable(String message, List<String> assumptions,
                                         OffsetDateTime now) {
        return unpromisable(message, null, assumptions, now, null);
    }

    private CapacityOutcome unpromisable(String message, CtpResult.CtpSolverInfo solver,
                                         List<String> assumptions, OffsetDateTime now) {
        return unpromisable(message, solver, assumptions, now, null);
    }

    private CapacityOutcome unpromisable(String message, CtpResult.CtpSolverInfo solver,
                                         List<String> assumptions, OffsetDateTime now,
                                         LocalDate completionDate) {
        Map<String, Object> evidence = evidence("UNPROMISABLE", null, null, null, message,
                completionDate);
        return new CapacityOutcome(CtpVerdict.UNPROMISABLE, null, null, null, solver, evidence,
                assumptions, message);
    }

    private CapacityOutcome solverFailure(String message, CtpResult.CtpSolverInfo solver,
                                          List<String> assumptions, OffsetDateTime now) {
        Map<String, Object> evidence = evidence("SOLVER_TIMEOUT", null, null, null, message, null);
        return new CapacityOutcome(CtpVerdict.SOLVER_TIMEOUT, null, null, null, solver, evidence,
                assumptions, message);
    }

    private Map<String, Object> evidence(String kind, String resourceCode,
                                         OffsetDateTime start, OffsetDateTime end,
                                         String detail, LocalDate completionDate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("taskIds", List.of());
        item.put("resourceCode", resourceCode);
        item.put("start", start);
        item.put("end", end);
        item.put("detail", detail);
        item.put("completionDate", completionDate);
        return item;
    }

    private static TaskAssignment lastAssignment(List<TaskAssignment> assignments,
                                                 UUID orderId) {
        TaskAssignment last = null;
        for (TaskAssignment assignment : assignments) {
            if (!orderId.equals(assignment.orderId()) || assignment.endTime() == null) {
                continue;
            }
            if (last == null || assignment.endTime().isAfter(last.endTime())) {
                last = assignment;
            }
        }
        return last;
    }

    private static OffsetDateTime firstStart(List<TaskAssignment> assignments, UUID orderId) {
        OffsetDateTime first = null;
        for (TaskAssignment assignment : assignments) {
            if (!orderId.equals(assignment.orderId()) || assignment.startTime() == null) {
                continue;
            }
            if (first == null || assignment.startTime().isBefore(first)) {
                first = assignment.startTime();
            }
        }
        return first;
    }

    /**
     * 容量验证结论；只读。
     *
     * @param verdict 结论
     * @param promisedDate 承诺日期；仅 PROMISED 非空
     * @param promisedQty 承诺数量；仅 PROMISED 非空
     * @param capacityVerifiedAt 验证完成时间；仅 PROMISED 非空
     * @param solver 求解摘要；未启动求解时为空
     * @param evidence 容量证据（单条，追加在物料证据之后）
     * @param assumptions 容量假设
     * @param message 确定性说明
     */
    public record CapacityOutcome(
            CtpVerdict verdict,
            LocalDate promisedDate,
            BigDecimal promisedQty,
            OffsetDateTime capacityVerifiedAt,
            CtpResult.CtpSolverInfo solver,
            Map<String, Object> evidence,
            List<String> assumptions,
            String message
    ) {

        /**
         * 归一化集合与恒 false 的落库标记。
         */
        public CapacityOutcome {
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }

        /**
         * 是否真的形成承诺。
         *
         * @return 仅 PROMISED 为 true
         */
        public boolean promised() {
            return verdict == CtpVerdict.PROMISED;
        }
    }
}
