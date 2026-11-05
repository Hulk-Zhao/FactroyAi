package com.hengce.aps.solver.timefold.model;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.domain.variable.PreviousElementShadowVariable;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.timefold.calendar.ResourceAvailability;
import com.hengce.aps.solver.timefold.setup.SetupMatrixResolver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Timefold 计划任务：由 {@code SchedulingOrder.operations} 展开而来。
 *
 * <p>{@code startTime} 为占用开始时间（含换型），资源归属由所属 {@link ResourcePlan}
 * 的 List Variable 通过 {@link #resource} 反向影子变量确定；{@link #previousTask}
 * 提供同资源相邻前序，用于按换型矩阵（C12 §17.3）解析实际换型工时。</p>
 */
@PlanningEntity
public class TaskPlan {

    @PlanningId
    private String planningId;

    private UUID orderId;

    private String orderNo;

    private int operationIndex;

    private String operationName;

    private String operationCode;

    private String materialCode;

    /**
     * 产品族，用于同品连续生产软约束；由订单物料映射填充，未映射时为空。
     */
    private String productFamily;

    /**
     * 订单交期（不含时区），用于最小超期与交期优先软约束。
     */
    private LocalDate dueDate;

    /**
     * 订单优先级：URGENT / HIGH / NORMAL / LOW，用于优先级尊重软约束。
     */
    private String priority;

    private UUID operationTemplateId;

    private UUID primaryResourceId;

    private List<UUID> secondaryResourceIds = new ArrayList<>();

    private BigDecimal qty;

    private long staticSetupSeconds;

    private long runSeconds;

    private boolean frozen;

    /**
     * 增量重排中的“固定任务”标记：未受影响订单的既有任务由求解器结构性地固定，
     * 不参与任何 move；与 C21 的 {@code lockType} 正交。
     */
    @PlanningPin
    private boolean pinned;

    private UUID pinnedResourceId;

    private LocalDateTime pinnedStartTime;

    /**
     * 跨窗口下界（UTC）：本订单有 FROZEN 任务落在求解窗口起点之前时，取这些任务的
     * 最大结束时间；窗口内工序不得早于该时刻开始。为空表示无下界。
     *
     * <p>没有它，被窗口过滤掉的 FROZEN 前序在模型里“消失”，求解器会把后续工序
     * 排到已完工工序之前，得出 0hard 但现实不可执行的计划。</p>
     */
    private LocalDateTime orderNotBefore;

    /**
     * C21 锁定类型：NONE / TIME_WINDOW / RESOURCE / FROZEN；为空等价 NONE。
     */
    private String lockType;

    /**
     * C21 时间窗锁起点，闭区间端点（含本值）。
     */
    private LocalDateTime lockedFrom;

    /**
     * C21 时间窗锁终点，闭区间端点（含本值）。
     */
    private LocalDateTime lockedTo;

    /**
     * C21 资源锁允许的主资源集合；为空/null 视为未锁定。
     */
    private List<UUID> lockedResourceIds = new ArrayList<>();

    private List<SetupMatrixEntry> setupMatrixEntries = new ArrayList<>();

    private List<ResourceAvailability> availabilityRequirements = new ArrayList<>();

    @PlanningVariable(valueRangeProviderRefs = "startTimeRange")
    private LocalDateTime startTime;

    @InverseRelationShadowVariable(sourceVariableName = "tasks")
    private ResourcePlan resource;

    @PreviousElementShadowVariable(sourceVariableName = "tasks")
    private TaskPlan previousTask;

    /**
     * Timefold 克隆所需的无参构造。
     */
    public TaskPlan() {
    }

    /**
     * 全量构造。
     *
     * @param orderId 订单主键
     * @param orderNo 订单号
     * @param operationIndex 工序序号
     * @param operationName 工序名称
     * @param operationTemplateId 工序模板标识
     * @param primaryResourceId 主资源标识
     * @param secondaryResourceIds 辅助资源标识
     * @param qty 数量
     * @param setupSeconds 静态换型秒数
     * @param runSeconds 加工秒数
     * @param frozen 是否冻结
     * @param pinnedResourceId 冻结资源
     * @param pinnedStartTime 冻结占用开始时间
     */
    public TaskPlan(
            UUID orderId,
            String orderNo,
            int operationIndex,
            String operationName,
            UUID operationTemplateId,
            UUID primaryResourceId,
            List<UUID> secondaryResourceIds,
            BigDecimal qty,
            long setupSeconds,
            long runSeconds,
            boolean frozen,
            UUID pinnedResourceId,
            LocalDateTime pinnedStartTime) {
        this.planningId = String.valueOf(orderId) + ':' + operationIndex;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.operationIndex = operationIndex;
        this.operationName = operationName;
        this.operationTemplateId = operationTemplateId;
        this.primaryResourceId = primaryResourceId;
        this.secondaryResourceIds = secondaryResourceIds == null
                ? new ArrayList<>()
                : new ArrayList<>(secondaryResourceIds);
        this.qty = qty;
        this.staticSetupSeconds = setupSeconds;
        this.runSeconds = runSeconds;
        this.frozen = frozen;
        this.pinnedResourceId = pinnedResourceId;
        this.pinnedStartTime = pinnedStartTime;
    }

    /**
     * 加工结束时间；未初始化时为空。
     *
     * @return 结束时间
     */
    public LocalDateTime getEndTime() {
        return startTime == null ? null : startTime.plusSeconds(getSetupSeconds() + runSeconds);
    }

    /**
     * 加工开始时间（换型结束）；未初始化时为空。
     *
     * @return 加工开始时间
     */
    public LocalDateTime getProcessingStartTime() {
        return startTime == null ? null : startTime.plusSeconds(getSetupSeconds());
    }

    /**
     * 实际换型秒数：同资源有前序任务且命中换型矩阵时取矩阵值，否则回退静态 {@code setup_seconds}。
     *
     * @return 换型秒数
     */
    public long getSetupSeconds() {
        if (previousTask == null || setupMatrixEntries.isEmpty()) {
            return staticSetupSeconds;
        }
        Long resolved = SetupMatrixResolver.resolve(
                setupMatrixEntries,
                getResourceId(),
                getResourceGroupId(),
                operationCode,
                materialCode,
                productFamily,
                previousTask.getOperationCode(),
                previousTask.getMaterialCode(),
                previousTask.getProductFamily());
        return resolved == null ? staticSetupSeconds : resolved;
    }

    /**
     * 判断任务占用块是否完整落在主资源与辅助资源的可用时段内；无可用时段视为 7×24。
     *
     * @return {@code true} 表示满足日历约束
     */
    public boolean isWithinAvailability() {
        if (startTime == null) {
            return true;
        }
        LocalDateTime end = getEndTime();
        for (ResourceAvailability requirement : availabilityRequirements) {
            if (!requirement.allows(startTime, end)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 当前占用区间开始的资源（主资源含换型，辅助资源仅加工窗口）。
     *
     * @param resourceId 资源主键
     * @return 占用开始时间
     */
    public LocalDateTime getOccupancyStart(UUID resourceId) {
        if (resourceId == null || !resourceId.equals(getResourceId())) {
            return getProcessingStartTime();
        }
        return startTime;
    }

    /**
     * 任务在“资源—时间”维度上的占用切片：主资源按 {@code [startTime, endTime)}（含换型），
     * 辅助资源按 {@code [processingStart, endTime)}（仅加工窗口，C12 §17.4）。
     *
     * <p>供 {@link com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider}
     * 先按 {@code resourceId} 分组再在组内配对，避免全表 {@code forEachUniquePair} 的 O(n²)。
     * 返回顺序固定（主资源在前，辅助资源按声明顺序去重），保证约束口径与
     * {@link #getOccupiedResourceIds()} / {@link #getOccupancyStart(UUID)} 一致。</p>
     *
     * @return 占用切片；任务未排位时为空列表
     */
    public List<OccupancySlot> occupancySlots() {
        if (startTime == null) {
            return List.of();
        }
        UUID primary = getResourceId();
        if (primary == null) {
            return List.of();
        }
        List<OccupancySlot> slots = new ArrayList<>(1 + secondaryResourceIds.size());
        slots.add(new OccupancySlot(primary, startTime, getEndTime()));
        LocalDateTime processingStart = getProcessingStartTime();
        for (UUID secondary : new LinkedHashSet<>(secondaryResourceIds)) {
            if (secondary != null && !secondary.equals(primary)) {
                slots.add(new OccupancySlot(secondary, processingStart, getEndTime()));
            }
        }
        return slots;
    }

    /**
     * 单个资源上的占用区间。
     *
     * @param resourceId 资源主键
     * @param start 占用开始（主资源含换型，辅助资源为加工开始）
     * @param end 占用结束
     */
    public record OccupancySlot(UUID resourceId, LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 任务占用的全部资源（主资源 + 辅助资源，去重）。
     *
     * @return 资源主键
     */
    public List<UUID> getOccupiedResourceIds() {
        LinkedHashSet<UUID> occupied = new LinkedHashSet<>();
        if (getResourceId() != null) {
            occupied.add(getResourceId());
        }
        occupied.addAll(secondaryResourceIds);
        return List.copyOf(occupied);
    }

    public String getPlanningId() {
        return planningId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public int getOperationIndex() {
        return operationIndex;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getOperationCode() {
        return operationCode;
    }

    public void setOperationCode(String operationCode) {
        this.operationCode = operationCode;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getProductFamily() {
        return productFamily;
    }

    /**
     * 设置产品族；未映射时传空。
     *
     * @param productFamily 产品族
     */
    public void setProductFamily(String productFamily) {
        this.productFamily = productFamily;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /**
     * 设置订单交期；交期为空时不参与超期与交期优先软约束。
     *
     * @param dueDate 交期
     */
    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getPriority() {
        return priority;
    }

    /**
     * 设置订单优先级；为空时按 NORMAL 处理。
     *
     * @param priority URGENT / HIGH / NORMAL / LOW
     */
    public void setPriority(String priority) {
        this.priority = priority;
    }

    public UUID getOperationTemplateId() {
        return operationTemplateId;
    }

    public UUID getPrimaryResourceId() {
        return primaryResourceId;
    }

    public List<UUID> getSecondaryResourceIds() {
        return secondaryResourceIds;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public long getStaticSetupSeconds() {
        return staticSetupSeconds;
    }

    public long getRunSeconds() {
        return runSeconds;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * 是否为增量重排的固定任务（不参与 move）。
     *
     * @return {@code true} 表示固定
     */
    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public UUID getPinnedResourceId() {
        return pinnedResourceId;
    }

    public LocalDateTime getPinnedStartTime() {
        return pinnedStartTime;
    }

    /**
     * 跨窗口下界（UTC）；为空表示该订单没有落在窗口起点之前的 FROZEN 任务。
     *
     * @return 下界时刻
     */
    public LocalDateTime getOrderNotBefore() {
        return orderNotBefore;
    }

    /**
     * 设置跨窗口下界。
     *
     * @param orderNotBefore 下界时刻（UTC），可空
     */
    public void setOrderNotBefore(LocalDateTime orderNotBefore) {
        this.orderNotBefore = orderNotBefore;
    }

    public String getLockType() {
        return lockType;
    }

    public void setLockType(String lockType) {
        this.lockType = lockType;
    }

    public LocalDateTime getLockedFrom() {
        return lockedFrom;
    }

    public void setLockedFrom(LocalDateTime lockedFrom) {
        this.lockedFrom = lockedFrom;
    }

    public LocalDateTime getLockedTo() {
        return lockedTo;
    }

    public void setLockedTo(LocalDateTime lockedTo) {
        this.lockedTo = lockedTo;
    }

    /**
     * 资源锁允许的主资源集合；永不为 {@code null}。
     *
     * @return 资源主键列表
     */
    public List<UUID> getLockedResourceIds() {
        return lockedResourceIds;
    }

    public void setLockedResourceIds(List<UUID> lockedResourceIds) {
        this.lockedResourceIds = lockedResourceIds == null
                ? new ArrayList<>()
                : new ArrayList<>(lockedResourceIds);
    }

    public List<SetupMatrixEntry> getSetupMatrixEntries() {
        return setupMatrixEntries;
    }

    public void setSetupMatrixEntries(List<SetupMatrixEntry> setupMatrixEntries) {
        this.setupMatrixEntries = setupMatrixEntries == null ? new ArrayList<>() : setupMatrixEntries;
    }

    public List<ResourceAvailability> getAvailabilityRequirements() {
        return availabilityRequirements;
    }

    public void setAvailabilityRequirements(List<ResourceAvailability> availabilityRequirements) {
        this.availabilityRequirements = availabilityRequirements == null
                ? new ArrayList<>()
                : availabilityRequirements;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public ResourcePlan getResource() {
        return resource;
    }

    public void setResource(ResourcePlan resource) {
        this.resource = resource;
    }

    public TaskPlan getPreviousTask() {
        return previousTask;
    }

    public void setPreviousTask(TaskPlan previousTask) {
        this.previousTask = previousTask;
    }

    /**
     * 当前任务所在资源主键。
     *
     * @return 资源主键，未分配时为空
     */
    public UUID getResourceId() {
        return resource == null ? null : resource.getResourceId();
    }

    /**
     * 当前任务所在资源组主键，用于 C43 资源组级换型矩阵匹配。
     *
     * @return 资源组主键，未分配或未维护时为空
     */
    public UUID getResourceGroupId() {
        return resource == null || resource.getSource() == null
                ? null : resource.getSource().groupId();
    }
}
