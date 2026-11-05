package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.AdjustTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.TaskAdjustmentRepository.AdjustmentRow;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.service.audit.AuditValues;
import com.hengce.aps.service.masterdata.order.FreezeLock;
import com.hengce.aps.service.masterdata.order.OrderView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 甘特人工调整服务（C22）。
 *
 * <p>调整语义：</p>
 * <ul>
 *   <li>{@code MOVE}：平移加工窗口（保持时长），可换主资源；{@code FROZEN} 任务拒绝；</li>
 *   <li>{@code LOCK}：写 {@code lock_type/locked_from/locked_to/locked_resource_ids}（V5）；</li>
 *   <li>{@code FREEZE}/{@code UNFREEZE}：切 {@code status} 与锁类型；{@code FREEZE} 必须带 reason；</li>
 *   <li>{@code SPLIT}（C36）：源任务保留第 1 段，其余按 {@code splitQuantities} 新建，时间按比例连续切分；</li>
 *   <li>{@code MERGE}（C36）：同资源/同工序/资源内连续，目标保留最早开始，数量求和，其余软删。</li>
 * </ul>
 *
 * <p>两层冻结取交集（C21 §24.3）：订单级 {@code freezeLock} 与任务级锁都是硬约束，
 * 任务级只能更窄；空交集在请求阶段直接 42201，message 同时列出订单约束与任务约束。
 * 版本过期 40901；已执行任务（IN_PROGRESS/COMPLETED）拒绝调整 40903。</p>
 *
 * <p>{@code dryRun=true} 只做完整校验与“将要写入”的视图计算，不写 {@code task} 与
 * {@code task_adjustment}。</p>
 */
@Service
public class TaskAdjustService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String LOCK_NONE = "NONE";

    private static final String LOCK_TIME_WINDOW = "TIME_WINDOW";

    private static final String LOCK_RESOURCE = "RESOURCE";

    private static final String LOCK_FROZEN = "FROZEN";

    private static final String STATUS_PLANNED = "PLANNED";

    private static final String STATUS_FROZEN = "FROZEN";

    private static final String ORDER_FROZEN = "FROZEN";

    private static final String ORDER_LOCKED_WINDOW = "LOCKED_TIME_WINDOW";

    private static final String ORDER_LOCKED_RESOURCE = "LOCKED_RESOURCE";

    private static final String OPERATION_SPLIT = "SPLIT";

    private static final String OPERATION_MERGE = "MERGE";

    private static final int MIN_ADJUST_QUANTITY_ITEMS = 2;

    private static final int MAX_ADJUST_QUANTITY_ITEMS = 10;

    private static final int MAX_REASON_LENGTH = 512;

    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.0001");

    private static final String AUDIT_TASK_ADJUSTED = "TASK_ADJUSTED";

    private static final String AUDIT_RESOURCE_TASK = "TASK";

    private final TaskAdjustmentRepository adjustmentRepository;

    private final ScheduleRepository scheduleRepository;

    private final CalendarMapper calendarMapper;

    private final ScheduleProblemAssembler assembler;

    private final AuditLogRepository auditLogRepository;

    /**
     * 构造服务。
     *
     * @param adjustmentRepository 调整持久层
     * @param scheduleRepository 排产持久层（资源编码映射）
     * @param calendarMapper 日历 Mapper（可用时段校验）
     * @param assembler 问题装配器，复用其日历窗口展开逻辑
     * @param auditLogRepository 审计日志追加写入
     */
    public TaskAdjustService(TaskAdjustmentRepository adjustmentRepository,
                             ScheduleRepository scheduleRepository,
                             CalendarMapper calendarMapper,
                             ScheduleProblemAssembler assembler,
                             AuditLogRepository auditLogRepository) {
        this.adjustmentRepository = adjustmentRepository;
        this.scheduleRepository = scheduleRepository;
        this.calendarMapper = calendarMapper;
        this.assembler = assembler;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 人工调整任务。
     *
     * @param taskId 任务主键
     * @param command 调整命令
     * @param idempotencyKey 幂等键（必填，缺失 40000；重复键由全局拦截器返回 40900）
     * @return 调整结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAdjustResult adjust(long taskId, TaskAdjustCommand command, String idempotencyKey) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        requireIdempotencyKey(idempotencyKey);
        String operation = normalizeOperation(command.getOperation());
        if (command.getVersion() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 必填");
        }
        UUID tenantId = TenantContext.getTenantId();
        AdjustTaskRow row = adjustmentRepository.findTask(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId));
        if (command.getVersion() != row.version()) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "任务版本已变化（当前 " + row.version() + "，请求 " + command.getVersion() + "），请刷新后重试");
        }
        rejectExecuted(row);
        boolean dryRun = Boolean.TRUE.equals(command.getDryRun());
        if (OPERATION_SPLIT.equals(operation)) {
            return applySplit(tenantId, row, command, dryRun);
        }
        if (OPERATION_MERGE.equals(operation)) {
            return applyMerge(tenantId, row, command, dryRun);
        }

        PlannedState planned = plan(tenantId, row, operation, command);
        validate(tenantId, row, operation, planned);
        Map<String, Object> before = snapshot(tenantId, row);
        Map<String, Object> after = snapshot(planned);
        if (dryRun) {
            return new TaskAdjustResult(null, true, true, toView(tenantId, planned, row.version()),
                    List.of(), List.of(), List.of());
        }
        int updated = adjustmentRepository.updateTask(tenantId, taskId, row.version(),
                planned.resourceId(), planned.startTime(), planned.endTime(),
                planned.setupStartTime(), planned.setupEndTime(), planned.status(), planned.lockType(),
                planned.lockedFrom(), planned.lockedTo(), toJson(planned.lockedResourceIds()),
                null, null);
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        long adjustmentId = adjustmentRepository.insertAdjustment(tenantId, taskId, row.sandboxId(),
                operation, toJson(before), toJson(after), trimToNull(command.getReason()),
                AuditContext.getCurrentUserId());
        writeAudit(tenantId, taskId, before, after);
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, planned, row.version() + 1), List.of(), List.of(), List.of());
    }

    /**
     * 撤销最近一次调整（按任务栈）。
     *
     * @param adjustmentId 调整记录主键
     * @return 撤销后的任务视图
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAdjustResult undo(long adjustmentId) {
        UUID tenantId = TenantContext.getTenantId();
        AdjustmentRow target = requireAdjustment(tenantId, adjustmentId);
        if (target.undone()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该调整已撤销，无需重复撤销");
        }
        AdjustmentRow latest = adjustmentRepository.findLatestActive(tenantId, target.taskId())
                .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT, "找不到可撤销的调整"));
        if (latest.id() != target.id()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "只能撤销最近一次调整（最近记录 " + latest.id() + "）");
        }
        AdjustTaskRow row = requireTask(tenantId, target.taskId());
        Map<String, Object> before = parseJson(target.beforeJson());
        Map<String, Object> after = parseJson(target.afterJson());
        PlannedState restored = fromSnapshot(tenantId, before);
        int updated = adjustmentRepository.updateTask(tenantId, row.taskId(), row.version(),
                restored.resourceId(), restored.startTime(), restored.endTime(),
                restored.setupStartTime(), restored.setupEndTime(), restored.status(), restored.lockType(),
                restored.lockedFrom(), restored.lockedTo(), toJson(restored.lockedResourceIds()),
                decimalValue(before.get("qty"), null), intValue(before.get("sequenceInResource")));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        List<Long> removedTaskIds = List.of();
        if (OPERATION_SPLIT.equals(target.adjustType())) {
            removedTaskIds = longList(after.get("createdTaskIds"));
            for (Long createdId : removedTaskIds) {
                adjustmentRepository.softDeleteTask(tenantId, createdId);
            }
            shiftBackAfterSplit(tenantId, row, before, removedTaskIds.size());
        }
        if (OPERATION_MERGE.equals(target.adjustType())) {
            restoreRemovedTasks(tenantId, before.get("removedTasks"));
        }
        adjustmentRepository.markUndone(tenantId, adjustmentId, true);
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, restored, row.version() + 1), List.of(), List.of(), removedTaskIds);
    }

    /**
     * 重做已撤销的调整。
     *
     * @param adjustmentId 调整记录主键
     * @return 重做后的任务视图
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskAdjustResult redo(long adjustmentId) {
        UUID tenantId = TenantContext.getTenantId();
        AdjustmentRow target = requireAdjustment(tenantId, adjustmentId);
        if (!target.undone()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该调整未撤销，无需重做");
        }
        if (adjustmentRepository.existsLaterAdjustment(tenantId, target.taskId(), target.id())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该调整之后已有新调整，不能重做");
        }
        AdjustTaskRow row = requireTask(tenantId, target.taskId());
        Map<String, Object> after = parseJson(target.afterJson());
        PlannedState restored = fromSnapshot(tenantId, after);
        int updated = adjustmentRepository.updateTask(tenantId, row.taskId(), row.version(),
                restored.resourceId(), restored.startTime(), restored.endTime(),
                restored.setupStartTime(), restored.setupEndTime(), restored.status(), restored.lockType(),
                restored.lockedFrom(), restored.lockedTo(), toJson(restored.lockedResourceIds()),
                decimalValue(after.get("qty"), null), intValue(after.get("sequenceInResource")));
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        List<Long> removedTaskIds = List.of();
        if (OPERATION_SPLIT.equals(target.adjustType())) {
            shiftForwardBeforeRedoSplit(tenantId, row, after);
            for (Long createdId : longList(after.get("createdTaskIds"))) {
                adjustmentRepository.undeleteTask(tenantId, createdId);
            }
        }
        if (OPERATION_MERGE.equals(target.adjustType())) {
            removedTaskIds = longList(after.get("removedTaskIds"));
            for (Long removedId : removedTaskIds) {
                adjustmentRepository.softDeleteTask(tenantId, removedId);
            }
        }
        adjustmentRepository.markUndone(tenantId, adjustmentId, false);
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, restored, row.version() + 1), List.of(), List.of(), removedTaskIds);
    }

    /**
     * 分页查询任务调整历史。
     *
     * @param taskId 任务主键
     * @param page 页码，1 起始
     * @param size 页大小
     * @return 历史分页
     */
    public AdjustmentPage history(long taskId, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(ApsConstants.MAX_PAGE_SIZE, Math.max(1, size));
        int total = adjustmentRepository.countHistory(tenantId, taskId);
        List<TaskAdjustmentView> items = adjustmentRepository
                .findHistory(tenantId, taskId, safeSize, (safePage - 1) * safeSize).stream()
                .map(row -> new TaskAdjustmentView(row.id(), row.adjustType(), parseJson(row.beforeJson()),
                        parseJson(row.afterJson()), row.reason(), row.undone(), row.createdAt()))
                .toList();
        return new AdjustmentPage(items, total, safePage, safeSize);
    }

    private void rejectExecuted(AdjustTaskRow row) {
        if ("IN_PROGRESS".equals(row.status()) || "COMPLETED".equals(row.status())) {
            throw new BizException(ErrorCode.PUBLISHED_CONFLICT,
                    "任务已执行（" + row.status() + "），禁止人工调整");
        }
    }

    private PlannedState plan(UUID tenantId, AdjustTaskRow row, String operation, TaskAdjustCommand command) {
        return switch (operation) {
            case "MOVE" -> planMove(tenantId, row, command);
            case "LOCK" -> planLock(tenantId, row, command);
            case "FREEZE" -> planFreeze(row, command);
            case "UNFREEZE" -> planUnfreeze(row);
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 operation: " + operation);
        };
    }

    private PlannedState planMove(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command) {
        if (STATUS_FROZEN.equals(row.status()) || LOCK_FROZEN.equals(row.lockType())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务处于 FROZEN，拒绝 MOVE: taskId=" + row.taskId());
        }
        if (command.getStartTime() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MOVE 必须提供 startTime");
        }
        if (row.startTime() == null || row.endTime() == null) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务缺少当前加工时间，无法按 MOVE 平移: taskId=" + row.taskId());
        }
        Duration duration = Duration.between(row.startTime(), row.endTime());
        OffsetDateTime start = command.getStartTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = start.plus(duration);
        Duration setup = row.setupStartTime() == null || row.setupEndTime() == null
                ? Duration.ZERO : Duration.between(row.setupStartTime(), row.setupEndTime());
        OffsetDateTime setupEnd = start;
        OffsetDateTime setupStart = setup.isZero() ? start : start.minus(setup);
        UUID resourceId = row.resourceId();
        String resourceCode = row.resourceCode();
        if (command.getResourceCode() != null && !command.getResourceCode().isBlank()) {
            TaskAdjustmentRepository.ResourceRef ref = requireResource(tenantId, command.getResourceCode());
            resourceId = ref.resourceId();
            resourceCode = ref.code();
        }
        return new PlannedState(resourceId, resourceCode, setupStart, setupEnd, start, end,
                row.status(), row.lockType(), row.lockedFrom(), row.lockedTo(), lockedIdsOf(row), row.operationIndex());
    }

    private PlannedState planLock(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command) {
        if (STATUS_FROZEN.equals(row.status()) || LOCK_FROZEN.equals(row.lockType())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务处于 FROZEN，LOCK 不得放宽其约束: taskId=" + row.taskId());
        }
        if (command.getLockFrom() == null || command.getLockTo() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LOCK 必须提供 lockFrom/lockTo");
        }
        OffsetDateTime from = command.getLockFrom().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime to = command.getLockTo().withOffsetSameInstant(ZoneOffset.UTC);
        if (!to.isAfter(from)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "lockTo 必须晚于 lockFrom");
        }
        List<String> codes = command.getLockedResourceCodes() == null ? List.of()
                : command.getLockedResourceCodes().stream().filter(code -> code != null && !code.isBlank())
                .map(String::trim).distinct().toList();
        List<UUID> ids = new ArrayList<>(codes.size());
        for (String code : codes) {
            ids.add(requireResource(tenantId, code).resourceId());
        }
        String lockType = ids.isEmpty() ? LOCK_TIME_WINDOW : LOCK_RESOURCE;
        return new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), row.startTime(), row.endTime(), row.status(), lockType,
                from, to, List.copyOf(ids), row.operationIndex());
    }

    private PlannedState planFreeze(AdjustTaskRow row, TaskAdjustCommand command) {
        if (trimToNull(command.getReason()) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "FREEZE 必须提供 reason");
        }
        return new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), row.startTime(), row.endTime(), STATUS_FROZEN, LOCK_FROZEN,
                null, null, List.of(), row.operationIndex());
    }

    private PlannedState planUnfreeze(AdjustTaskRow row) {
        return new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), row.startTime(), row.endTime(),
                STATUS_FROZEN.equals(row.status()) ? STATUS_PLANNED : row.status(),
                LOCK_NONE, null, null, List.of(), row.operationIndex());
    }

    /**
     * SPLIT（C36 §25.4.2）：源任务保留第 1 段，其余段新建任务；时间按数量比例连续切分。
     */
    private TaskAdjustResult applySplit(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command,
                                        boolean dryRun) {
        SplitPlan splitPlan = planSplit(tenantId, row, command);
        Segment first = splitPlan.segments().get(0);
        PlannedState source = new PlannedState(row.resourceId(), row.resourceCode(), row.setupStartTime(),
                row.setupEndTime(), first.start(), first.end(), row.status(), row.lockType(),
                row.lockedFrom(), row.lockedTo(), lockedIdsOf(row), row.operationIndex());
        if (dryRun) {
            return new TaskAdjustResult(null, true, true, toView(tenantId, source, row.version()),
                    List.of(), List.of(), List.of());
        }
        int updated = adjustmentRepository.updateTask(tenantId, row.taskId(), row.version(),
                source.resourceId(), source.startTime(), source.endTime(), source.setupStartTime(),
                source.setupEndTime(), source.status(), source.lockType(), source.lockedFrom(),
                source.lockedTo(), toJson(source.lockedResourceIds()),
                splitPlan.quantities().get(0), null);
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        int childCount = splitPlan.quantities().size() - 1;
        Integer baseSequence = row.sequenceInResource();
        if (baseSequence != null && row.resourceId() != null) {
            adjustmentRepository.shiftSequence(tenantId, row.sandboxId(), row.resourceId(),
                    baseSequence, childCount);
        }
        List<Long> createdTaskIds = new ArrayList<>(childCount);
        for (int index = 1; index <= childCount; index++) {
            Segment segment = splitPlan.segments().get(index);
            Integer sequence = baseSequence == null ? null : baseSequence + index;
            createdTaskIds.add(adjustmentRepository.insertSplitTask(tenantId,
                    new TaskAdjustmentRepository.NewSplitTask(row.orderId(), row.sandboxId(),
                            row.operationIndex(), row.operationTemplateId(), splitPlan.childResourceId(),
                            row.secondaryJson(), segment.start(), segment.end(),
                            splitPlan.quantities().get(index), sequence),
                    AuditContext.getCurrentUserId()));
        }
        Map<String, Object> before = snapshot(tenantId, row);
        before.put("taskIds", List.of(row.taskId()));
        Map<String, Object> after = snapshot(source);
        after.put("qty", splitPlan.quantities().get(0));
        after.put("taskIds", taskIdsOf(row.taskId(), createdTaskIds));
        after.put("operation", OPERATION_SPLIT);
        after.put("parentTaskId", row.taskId());
        after.put("createdTaskIds", createdTaskIds);
        long adjustmentId = adjustmentRepository.insertAdjustment(tenantId, row.taskId(), row.sandboxId(),
                OPERATION_SPLIT, toJson(before), toJson(after), trimToNull(command.getReason()),
                AuditContext.getCurrentUserId());
        writeAudit(tenantId, row.taskId(), auditBefore(before), auditAfter(after));
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, source, row.version() + 1), List.of(), createdTaskIds, List.of());
    }

    private SplitPlan planSplit(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command) {
        requireReason(command, OPERATION_SPLIT);
        if (STATUS_FROZEN.equals(row.status()) || LOCK_FROZEN.equals(row.lockType())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务处于 FROZEN，禁止 SPLIT: taskId=" + row.taskId());
        }
        List<BigDecimal> quantities = command.getSplitQuantities();
        if (quantities == null || quantities.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "SPLIT 必须提供 splitQuantities");
        }
        if (quantities.size() < MIN_ADJUST_QUANTITY_ITEMS || quantities.size() > MAX_ADJUST_QUANTITY_ITEMS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "splitQuantities 需为 2–10 项: 实际 " + quantities.size() + " 项");
        }
        BigDecimal sourceQty = row.qty();
        if (sourceQty == null || sourceQty.signum() <= 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "SPLIT 要求源任务 qty 非空且 > 0: taskId=" + row.taskId() + ", qty=" + sourceQty);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal quantity : quantities) {
            if (quantity == null || quantity.signum() <= 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "splitQuantities 每项必须 > 0: " + quantities);
            }
            total = total.add(quantity);
        }
        if (total.subtract(sourceQty).abs().compareTo(QTY_TOLERANCE) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "splitQuantities 之和必须等于源任务 qty（容差 1e-4）: 合计 " + total
                            + ", 源 qty=" + sourceQty);
        }
        UUID childResourceId = row.resourceId();
        if (command.getResourceCode() != null && !command.getResourceCode().isBlank()) {
            childResourceId = requireResource(tenantId, command.getResourceCode()).resourceId();
        }
        return new SplitPlan(List.copyOf(quantities), splitSegments(row, quantities, total), childResourceId);
    }

    private List<Segment> splitSegments(AdjustTaskRow row, List<BigDecimal> quantities, BigDecimal total) {
        List<Segment> segments = new ArrayList<>(quantities.size());
        if (row.startTime() == null || row.endTime() == null) {
            for (int index = 0; index < quantities.size(); index++) {
                segments.add(new Segment(null, null));
            }
            return segments;
        }
        long totalNanos = Duration.between(row.startTime(), row.endTime()).toNanos();
        BigDecimal cumulative = BigDecimal.ZERO;
        OffsetDateTime from = row.startTime();
        for (int index = 0; index < quantities.size(); index++) {
            cumulative = cumulative.add(quantities.get(index));
            OffsetDateTime to = index == quantities.size() - 1 ? row.endTime()
                    : row.startTime().plusNanos(BigDecimal.valueOf(totalNanos)
                    .multiply(cumulative.divide(total, 12, RoundingMode.HALF_UP))
                    .setScale(0, RoundingMode.HALF_UP).longValue());
            segments.add(new Segment(from, to));
            from = to;
        }
        return segments;
    }

    /**
     * MERGE（C36 §25.4.2）：同资源/同工序/资源内连续，保留路径任务为目标，数量求和、取其最早开始。
     */
    private TaskAdjustResult applyMerge(UUID tenantId, AdjustTaskRow row, TaskAdjustCommand command,
                                        boolean dryRun) {
        MergePlan mergePlan = planMerge(tenantId, row, command);
        AdjustTaskRow target = mergePlan.target();
        PlannedState merged = new PlannedState(target.resourceId(), target.resourceCode(),
                mergePlan.setupStartTime(), mergePlan.setupEndTime(), mergePlan.startTime(),
                mergePlan.endTime(), target.status(), target.lockType(), target.lockedFrom(),
                target.lockedTo(), lockedIdsOf(target), target.operationIndex());
        if (dryRun) {
            return new TaskAdjustResult(null, true, true, toView(tenantId, merged, row.version()),
                    List.of(), List.of(), List.of());
        }
        int updated = adjustmentRepository.updateTask(tenantId, target.taskId(), target.version(),
                merged.resourceId(), merged.startTime(), merged.endTime(), merged.setupStartTime(),
                merged.setupEndTime(), merged.status(), merged.lockType(), merged.lockedFrom(),
                merged.lockedTo(), toJson(merged.lockedResourceIds()), mergePlan.mergedQty(), null);
        if (updated == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务版本已变化，请刷新后重试");
        }
        List<Long> removedTaskIds = new ArrayList<>();
        List<Map<String, Object>> removedTasks = new ArrayList<>();
        for (AdjustTaskRow participant : mergePlan.participants()) {
            if (participant.taskId() == target.taskId()) {
                continue;
            }
            adjustmentRepository.softDeleteTask(tenantId, participant.taskId());
            removedTaskIds.add(participant.taskId());
            removedTasks.add(taskSnapshot(participant));
        }
        Map<String, Object> before = snapshot(tenantId, target);
        before.put("qty", target.qty());
        before.put("taskIds", mergePlan.participants().stream().map(AdjustTaskRow::taskId).toList());
        before.put("removedTasks", removedTasks);
        Map<String, Object> after = snapshot(merged);
        after.put("qty", mergePlan.mergedQty());
        after.put("taskIds", List.of(target.taskId()));
        after.put("operation", OPERATION_MERGE);
        after.put("removedTaskIds", removedTaskIds);
        long adjustmentId = adjustmentRepository.insertAdjustment(tenantId, target.taskId(),
                target.sandboxId(), OPERATION_MERGE, toJson(before), toJson(after),
                trimToNull(command.getReason()), AuditContext.getCurrentUserId());
        writeAudit(tenantId, target.taskId(), auditBefore(before), auditAfter(after));
        return new TaskAdjustResult(adjustmentId, true, false,
                toView(tenantId, merged, target.version() + 1), List.of(), List.of(), removedTaskIds);
    }

    private MergePlan planMerge(UUID tenantId, AdjustTaskRow pathRow, TaskAdjustCommand command) {
        requireReason(command, OPERATION_MERGE);
        List<Long> mergeTaskIds = command.getMergeTaskIds();
        if (mergeTaskIds == null || mergeTaskIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MERGE 必须提供 mergeTaskIds");
        }
        if (mergeTaskIds.size() < MIN_ADJUST_QUANTITY_ITEMS
                || mergeTaskIds.size() > MAX_ADJUST_QUANTITY_ITEMS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "mergeTaskIds 需为 2–10 项: 实际 " + mergeTaskIds.size() + " 项");
        }
        if (mergeTaskIds.stream().anyMatch(Objects::isNull)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "mergeTaskIds 不允许包含空值");
        }
        if (new HashSet<>(mergeTaskIds).size() != mergeTaskIds.size()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "mergeTaskIds 不允许重复: " + mergeTaskIds);
        }
        if (!mergeTaskIds.contains(pathRow.taskId())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "mergeTaskIds 必须包含路径任务 id: " + pathRow.taskId());
        }
        List<AdjustTaskRow> participants = new ArrayList<>(mergeTaskIds.size());
        for (Long taskId : mergeTaskIds) {
            participants.add(adjustmentRepository.findTask(tenantId, taskId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId)));
        }
        for (AdjustTaskRow participant : participants) {
            if ("IN_PROGRESS".equals(participant.status()) || "COMPLETED".equals(participant.status())) {
                throw new BizException(ErrorCode.PUBLISHED_CONFLICT,
                        "任务已执行（" + participant.status() + "），禁止 MERGE: taskId="
                                + participant.taskId());
            }
            if (STATUS_FROZEN.equals(participant.status()) || LOCK_FROZEN.equals(participant.lockType())) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "任务处于 FROZEN，禁止 MERGE: taskId=" + participant.taskId());
            }
            if (participant.qty() == null || participant.qty().signum() <= 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "MERGE 要求所有参与任务 qty 非空且 > 0: taskId=" + participant.taskId()
                                + ", qty=" + participant.qty());
            }
            if (!Objects.equals(participant.sandboxId(), pathRow.sandboxId())
                    || !Objects.equals(participant.orderId(), pathRow.orderId())) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "MERGE 要求同租户/同场景/同订单: taskId=" + participant.taskId()
                                + ", sandboxId=" + participant.sandboxId() + ", orderId=" + participant.orderId());
            }
            if (participant.operationIndex() != pathRow.operationIndex()) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "MERGE 要求同工序: taskId=" + participant.taskId()
                                + ", operationIndex=" + participant.operationIndex());
            }
            if (!Objects.equals(participant.resourceId(), pathRow.resourceId())) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "MERGE 要求同资源: taskId=" + participant.taskId()
                                + ", resourceId=" + participant.resourceId());
            }
        }
        participants.sort(Comparator.comparing(AdjustTaskRow::sequenceInResource,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(AdjustTaskRow::taskId));
        for (int index = 1; index < participants.size(); index++) {
            AdjustTaskRow previous = participants.get(index - 1);
            AdjustTaskRow current = participants.get(index);
            if (previous.sequenceInResource() == null || current.sequenceInResource() == null
                    || previous.sequenceInResource() + 1 != current.sequenceInResource()) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "MERGE 要求资源内连续相邻: taskIds=" + mergeTaskIds
                                + ", sequences=" + participants.stream()
                                .map(AdjustTaskRow::sequenceInResource).toList());
            }
        }
        BigDecimal mergedQty = participants.stream().map(AdjustTaskRow::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OffsetDateTime start = participants.stream().map(AdjustTaskRow::startTime)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        OffsetDateTime end = participants.stream().map(AdjustTaskRow::endTime)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        AdjustTaskRow earliest = participants.get(0);
        for (AdjustTaskRow participant : participants) {
            if (participant.startTime() != null
                    && (earliest.startTime() == null || participant.startTime().isBefore(earliest.startTime()))) {
                earliest = participant;
            }
        }
        return new MergePlan(pathRow, participants, mergedQty, start, end,
                earliest.setupStartTime(), earliest.setupEndTime());
    }

    private void requireReason(TaskAdjustCommand command, String operation) {
        String reason = trimToNull(command.getReason());
        if (reason == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, operation + " 必须提供 reason");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "reason 长度不能超过 " + MAX_REASON_LENGTH + "：" + reason.length());
        }
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Idempotency-Key 必填");
        }
    }

    private void writeAudit(UUID tenantId, long taskId, Map<String, Object> before,
                            Map<String, Object> after) {
        auditLogRepository.record(tenantId, AUDIT_TASK_ADJUSTED, AUDIT_RESOURCE_TASK,
                String.valueOf(taskId), AuditValues.redact(before), AuditValues.redact(after));
    }

    private static Map<String, Object> auditBefore(Map<String, Object> snapshot) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("qty", snapshot.get("qty"));
        audit.put("taskIds", snapshot.getOrDefault("taskIds", List.of()));
        return audit;
    }

    private static Map<String, Object> auditAfter(Map<String, Object> snapshot) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("qty", snapshot.get("qty"));
        audit.put("taskIds", snapshot.getOrDefault("taskIds", List.of()));
        audit.put("operation", snapshot.get("operation"));
        return audit;
    }

    private static Map<String, Object> taskSnapshot(AdjustTaskRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskId", row.taskId());
        snapshot.put("qty", row.qty());
        snapshot.put("startTime", text(row.startTime()));
        snapshot.put("endTime", text(row.endTime()));
        snapshot.put("setupStartTime", text(row.setupStartTime()));
        snapshot.put("setupEndTime", text(row.setupEndTime()));
        snapshot.put("sequenceInResource", row.sequenceInResource());
        return snapshot;
    }

    private void restoreRemovedTasks(UUID tenantId, Object value) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Long taskId = longValue(map.get("taskId"));
            if (taskId == null) {
                continue;
            }
            adjustmentRepository.restoreMergedTask(tenantId, taskId,
                    decimalValue(map.get("qty"), null),
                    timeValue(map.get("startTime")), timeValue(map.get("endTime")),
                    timeValue(map.get("setupStartTime")), timeValue(map.get("setupEndTime")),
                    intValue(map.get("sequenceInResource")));
        }
    }

    private void shiftBackAfterSplit(UUID tenantId, AdjustTaskRow row, Map<String, Object> before,
                                     int count) {
        if (count <= 0) {
            return;
        }
        Integer sequence = intValue(before.get("sequenceInResource"));
        if (sequence == null || row.resourceId() == null) {
            return;
        }
        adjustmentRepository.shiftSequence(tenantId, row.sandboxId(), row.resourceId(), sequence, -count);
    }

    private void shiftForwardBeforeRedoSplit(UUID tenantId, AdjustTaskRow row, Map<String, Object> after) {
        Object created = after.get("createdTaskIds");
        int count = created instanceof List<?> list ? list.size() : 0;
        if (count <= 0) {
            return;
        }
        Integer sequence = intValue(after.get("sequenceInResource"));
        if (sequence == null || row.resourceId() == null) {
            return;
        }
        adjustmentRepository.shiftSequence(tenantId, row.sandboxId(), row.resourceId(), sequence, count);
    }

    private static List<Long> taskIdsOf(long mainTaskId, List<Long> createdTaskIds) {
        List<Long> ids = new ArrayList<>(createdTaskIds.size() + 1);
        ids.add(mainTaskId);
        ids.addAll(createdTaskIds);
        return ids;
    }

    /**
     * 可行性校验：两层冻结取交集 + 资源重叠 + 工序依赖 + 日历可用时段。
     *
     * @param tenantId 租户标识
     * @param row 调整前任务
     * @param operation 操作类型
     * @param planned 目标状态
     */
    private void validate(UUID tenantId, AdjustTaskRow row, String operation, PlannedState planned) {
        FreezeLock orderLock = orderLock(row);
        if (ORDER_FROZEN.equals(row.orderFreezeStatus()) && !"FREEZE".equals(operation)) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "订单级已冻结，任务级不可放宽: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(orderLock));
        }
        if (!"LOCK".equals(operation)) {
            if (ORDER_LOCKED_WINDOW.equals(row.orderFreezeStatus())) {
                requireInsideOrderWindow(row, orderLock, planned.startTime(), planned.endTime());
            }
            if (ORDER_LOCKED_RESOURCE.equals(row.orderFreezeStatus())) {
                requireSubsetOfOrderResources(row, orderLock, planned.resourceCode());
            }
            if (LOCK_TIME_WINDOW.equals(row.lockType())) {
                requireInsideTaskWindow(row, planned.startTime(), planned.endTime());
            }
            if (LOCK_RESOURCE.equals(row.lockType()) && planned.resourceId() != null
                    && !lockedIdsOf(row).contains(planned.resourceId())) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "任务资源锁不允许切换到其它资源: taskId=" + row.taskId()
                                + ", lockType=RESOURCE, lockedResourceIds=" + lockedIdsOf(row));
            }
        } else {
            requireTaskLockWithinOrder(row, orderLock, planned.lockedFrom(), planned.lockedTo(),
                    planned.lockedResourceIds());
        }
        if (!"MOVE".equals(operation)) {
            return;
        }
        if (planned.startTime() == null || planned.endTime() == null
                || !planned.endTime().isAfter(planned.startTime())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE, "调整后的加工窗口必须满足 endTime > startTime");
        }
        if (planned.resourceId() == null) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE, "MOVE 需要目标资源");
        }
        List<AdjustTaskRow> overlaps = adjustmentRepository.findOverlappingTasks(tenantId, row.sandboxId(),
                planned.resourceId(), row.taskId(), planned.startTime(), planned.endTime());
        if (!overlaps.isEmpty()) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "目标资源在 " + planned.startTime() + " – " + planned.endTime()
                            + " 已有任务占用: taskIds=" + overlaps.stream().map(AdjustTaskRow::taskId).toList());
        }
        requireDependencyOrder(tenantId, row, planned.startTime(), planned.endTime());
        requireCalendarAvailability(tenantId, planned);
    }

    private void requireInsideOrderWindow(AdjustTaskRow row, FreezeLock lock,
                                          OffsetDateTime start, OffsetDateTime end) {
        if (lock == null || lock.from() == null || lock.to() == null) {
            return;
        }
        if (start == null || end == null || start.isBefore(lock.from()) || end.isAfter(lock.to())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务窗口与订单锁窗无交集（任务级不得比订单级更宽）: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(lock) + ", taskId=" + row.taskId()
                            + ", taskWindow=" + start + " – " + end);
        }
    }

    private void requireSubsetOfOrderResources(AdjustTaskRow row, FreezeLock lock, String resourceCode) {
        if (lock == null || lock.normalizedResourceCodes().isEmpty()) {
            return;
        }
        if (resourceCode == null || !lock.normalizedResourceCodes().contains(resourceCode)) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务资源不在订单锁定资源集合内，任务级不得比订单级更宽: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(lock) + ", taskId=" + row.taskId()
                            + ", taskResource=" + resourceCode);
        }
    }

    private void requireInsideTaskWindow(AdjustTaskRow row, OffsetDateTime start, OffsetDateTime end) {
        if (row.lockedFrom() == null || row.lockedTo() == null) {
            return;
        }
        if (start == null || end == null || start.isBefore(row.lockedFrom()) || end.isAfter(row.lockedTo())) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务窗口超出任务时间锁: taskId=" + row.taskId() + ", lockType=TIME_WINDOW"
                            + ", lockedWindow=" + row.lockedFrom() + " – " + row.lockedTo()
                            + ", taskWindow=" + start + " – " + end);
        }
    }

    private void requireTaskLockWithinOrder(AdjustTaskRow row, FreezeLock orderLock,
                                            OffsetDateTime from, OffsetDateTime to, List<UUID> resourceIds) {
        if (orderLock == null) {
            return;
        }
        if (ORDER_LOCKED_WINDOW.equals(row.orderFreezeStatus()) && orderLock.from() != null
                && orderLock.to() != null
                && (from.isBefore(orderLock.from()) || to.isAfter(orderLock.to()))) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "任务锁窗超出订单锁窗，任务级不得比订单级更宽: orderNo=" + row.orderNo()
                            + ", freezeLock=" + describe(orderLock) + ", taskId=" + row.taskId()
                            + ", taskLockWindow=" + from + " – " + to);
        }
        if (ORDER_LOCKED_RESOURCE.equals(row.orderFreezeStatus())
                && !orderLock.normalizedResourceCodes().isEmpty() && !resourceIds.isEmpty()) {
            Map<UUID, String> codes = scheduleRepository.findResourceCodes(TenantContext.getTenantId());
            String widened = resourceIds.stream().map(codes::get)
                    .filter(code -> code == null || !orderLock.normalizedResourceCodes().contains(code))
                    .findFirst().orElse(null);
            if (widened != null) {
                throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                        "任务锁定资源超出订单锁定资源集合: orderNo=" + row.orderNo()
                                + ", freezeLock=" + describe(orderLock) + ", taskId=" + row.taskId()
                                + ", lockedResourceIds=" + resourceIds);
            }
        }
    }

    private void requireDependencyOrder(UUID tenantId, AdjustTaskRow row,
                                        OffsetDateTime start, OffsetDateTime end) {
        List<AdjustTaskRow> orderTasks = adjustmentRepository.findOrderTasks(
                tenantId, row.sandboxId(), row.orderId());
        List<Long> conflicts = new ArrayList<>();
        for (AdjustTaskRow other : orderTasks) {
            if (other.taskId() == row.taskId()) {
                continue;
            }
            if (other.operationIndex() < row.operationIndex() && other.endTime() != null
                    && other.endTime().isAfter(start)) {
                conflicts.add(other.taskId());
            }
            if (other.operationIndex() > row.operationIndex() && other.startTime() != null
                    && other.startTime().isBefore(end)) {
                conflicts.add(other.taskId());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "调整后违反工序前后依赖: taskId=" + row.taskId() + ", relatedTaskIds=" + conflicts);
        }
    }

    private void requireCalendarAvailability(UUID tenantId, PlannedState planned) {
        UUID calendarId = adjustmentRepository.findResourceByCode(tenantId, planned.resourceCode())
                .map(TaskAdjustmentRepository.ResourceRef::calendarId).orElse(null);
        if (calendarId == null) {
            return;
        }
        Calendar calendar = calendarMapper.findById(calendarId);
        OffsetDateTime from = planned.setupStartTime() == null ? planned.startTime() : planned.setupStartTime();
        if (calendar == null || planned.endTime() == null || from == null) {
            return;
        }
        List<AvailabilityWindow> windows = assembler.expand(calendar, from, planned.endTime());
        if (windows.isEmpty()) {
            return;
        }
        boolean contained = windows.stream().anyMatch(window ->
                !planned.startTime().isBefore(window.start()) && !planned.endTime().isAfter(window.end()));
        if (!contained) {
            throw new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE,
                    "调整后窗口落在资源日历非可用时段: resource=" + planned.resourceCode()
                            + ", window=" + planned.startTime() + " – " + planned.endTime());
        }
    }

    private AdjustTaskRow requireTask(UUID tenantId, long taskId) {
        return adjustmentRepository.findTask(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在: " + taskId));
    }

    private AdjustmentRow requireAdjustment(UUID tenantId, long adjustmentId) {
        return adjustmentRepository.findAdjustment(tenantId, adjustmentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "调整记录不存在: " + adjustmentId));
    }

    private TaskAdjustmentRepository.ResourceRef requireResource(UUID tenantId, String code) {
        return adjustmentRepository.findResourceByCode(tenantId, code.trim())
                .orElseThrow(() -> new BizException(ErrorCode.ADJUSTMENT_INFEASIBLE, "资源不存在: " + code));
    }

    private FreezeLock orderLock(AdjustTaskRow row) {
        if (row.orderExtAttrs() == null || row.orderExtAttrs().isBlank()) {
            return null;
        }
        return OrderView.parseFreezeLock(parseJson(row.orderExtAttrs()));
    }

    private static String describe(FreezeLock lock) {
        return lock == null ? "null"
                : "{from=" + lock.from() + ", to=" + lock.to() + ", resourceCodes="
                + lock.normalizedResourceCodes() + "}";
    }

    private List<UUID> lockedIdsOf(AdjustTaskRow row) {
        if (row.lockedResourcesJson() == null || row.lockedResourcesJson().isBlank()) {
            return List.of();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(row.lockedResourcesJson(),
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            List<UUID> ids = new ArrayList<>(values.size());
            for (String value : values) {
                ids.add(UUID.fromString(value));
            }
            return List.copyOf(ids);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    private Map<String, Object> snapshot(UUID tenantId, AdjustTaskRow row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceCode", row.resourceCode());
        snapshot.put("startTime", text(row.startTime()));
        snapshot.put("endTime", text(row.endTime()));
        snapshot.put("setupStartTime", text(row.setupStartTime()));
        snapshot.put("setupEndTime", text(row.setupEndTime()));
        snapshot.put("status", row.status());
        snapshot.put("lockType", row.lockType());
        snapshot.put("lockFrom", text(row.lockedFrom()));
        snapshot.put("lockTo", text(row.lockedTo()));
        snapshot.put("lockedResourceCodes", codesOf(tenantId, lockedIdsOf(row)));
        snapshot.put("qty", row.qty());
        snapshot.put("sequenceInResource", row.sequenceInResource());
        snapshot.put("version", row.version());
        return snapshot;
    }

    private Map<String, Object> snapshot(PlannedState planned) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceCode", planned.resourceCode());
        snapshot.put("startTime", text(planned.startTime()));
        snapshot.put("endTime", text(planned.endTime()));
        snapshot.put("setupStartTime", text(planned.setupStartTime()));
        snapshot.put("setupEndTime", text(planned.setupEndTime()));
        snapshot.put("status", planned.status());
        snapshot.put("lockType", planned.lockType());
        snapshot.put("lockFrom", text(planned.lockedFrom()));
        snapshot.put("lockTo", text(planned.lockedTo()));
        snapshot.put("lockedResourceCodes", planned.lockedResourceIds() == null ? List.of()
                : codesOf(TenantContext.getTenantId(), planned.lockedResourceIds()));
        snapshot.put("sequenceInResource", null);
        return snapshot;
    }

    private List<String> codesOf(UUID tenantId, List<UUID> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> codes = scheduleRepository.findResourceCodes(tenantId);
        return resourceIds.stream().map(codes::get).filter(code -> code != null).toList();
    }

    private PlannedState fromSnapshot(UUID tenantId, Map<String, Object> snapshot) {
        String resourceCode = stringValue(snapshot.get("resourceCode"));
        UUID resourceId = null;
        if (resourceCode != null) {
            TaskAdjustmentRepository.ResourceRef ref = adjustmentRepository
                    .findResourceByCode(tenantId, resourceCode)
                    .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT,
                            "快照中的资源已不存在: " + resourceCode));
            resourceId = ref.resourceId();
        }
        List<UUID> lockedIds = new ArrayList<>();
        Object codes = snapshot.get("lockedResourceCodes");
        if (codes instanceof List<?> list) {
            for (Object code : list) {
                String text = stringValue(code);
                if (text == null) {
                    continue;
                }
                lockedIds.add(adjustmentRepository.findResourceByCode(tenantId, text)
                        .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT,
                                "快照中的资源已不存在: " + text)).resourceId());
            }
        }
        return new PlannedState(resourceId, resourceCode,
                timeValue(snapshot.get("setupStartTime")), timeValue(snapshot.get("setupEndTime")),
                timeValue(snapshot.get("startTime")), timeValue(snapshot.get("endTime")),
                stringValue(snapshot.get("status")), stringValue(snapshot.get("lockType")),
                timeValue(snapshot.get("lockFrom")), timeValue(snapshot.get("lockTo")),
                List.copyOf(lockedIds), 0);
    }

    private TaskView toView(UUID tenantId, PlannedState planned, int version) {
        return new TaskView(planned.resourceCode(), planned.status(), version,
                planned.setupStartTime(), planned.setupEndTime(), planned.startTime(), planned.endTime(),
                planned.lockType(), planned.lockedFrom(), planned.lockedTo(),
                codesOf(tenantId, planned.lockedResourceIds()));
    }

    private static String normalizeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "operation 必填");
        }
        return operation.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("调整快照解析失败", exception);
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("调整快照序列化失败", exception);
        }
    }

    private static String text(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static OffsetDateTime timeValue(Object value) {
        String text = stringValue(value);
        return text == null ? null : OffsetDateTime.parse(text);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        return text == null ? null : Long.valueOf(text);
    }

    private static BigDecimal decimalValue(Object value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = stringValue(value);
        return text == null ? fallback : new BigDecimal(text);
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        return text == null ? null : Integer.valueOf(text);
    }

    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(list.size());
        for (Object item : list) {
            Long id = longValue(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 时间片段（SPLIT 切分结果）。
     *
     * @param start 片段开始，可空
     * @param end 片段结束，可空
     */
    private record Segment(OffsetDateTime start, OffsetDateTime end) {
    }

    /**
     * SPLIT 计划。
     *
     * @param quantities 各段数量
     * @param segments 各段时间片段
     * @param childResourceId 新建子任务资源主键
     */
    private record SplitPlan(List<BigDecimal> quantities, List<Segment> segments, UUID childResourceId) {
    }

    /**
     * MERGE 计划。
     *
     * @param target 目标（路径）任务
     * @param participants 参与任务，按资源内顺序升序
     * @param mergedQty 合并后数量
     * @param startTime 合并后开始
     * @param endTime 合并后结束
     * @param setupStartTime 换型开始（取最早任务）
     * @param setupEndTime 换型结束（取最早任务）
     */
    private record MergePlan(AdjustTaskRow target, List<AdjustTaskRow> participants, BigDecimal mergedQty,
                             OffsetDateTime startTime, OffsetDateTime endTime,
                             OffsetDateTime setupStartTime, OffsetDateTime setupEndTime) {
    }

    /**
     * 调整目标状态（写入前的完整字段集合）。
     */
    private record PlannedState(UUID resourceId, String resourceCode, OffsetDateTime setupStartTime,
                                OffsetDateTime setupEndTime, OffsetDateTime startTime,
                                OffsetDateTime endTime, String status, String lockType,
                                OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                                List<UUID> lockedResourceIds, int operationIndex) {
    }

    /**
     * 人工调整命令（C22 {@code TaskAdjustRequest}）。
     *
     * <p>使用可变 POJO 而非 record：Jackson 对 record 的规范化构造器要求全部组件参与绑定，
     * 这里需要支持“只传 operation + version”这类部分字段请求。</p>
     */
    public static class TaskAdjustCommand {

        private String operation;

        private String resourceCode;

        private List<String> secondaryResourceCodes;

        private OffsetDateTime startTime;

        private OffsetDateTime lockFrom;

        private OffsetDateTime lockTo;

        private List<String> lockedResourceCodes;

        private String reason;

        private Integer version;

        private Boolean dryRun;

        private List<BigDecimal> splitQuantities;

        private List<Long> mergeTaskIds;

        /**
         * 无参构造，供 Jackson 反序列化。
         */
        public TaskAdjustCommand() {
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public String getResourceCode() {
            return resourceCode;
        }

        public void setResourceCode(String resourceCode) {
            this.resourceCode = resourceCode;
        }

        public List<String> getSecondaryResourceCodes() {
            return secondaryResourceCodes;
        }

        public void setSecondaryResourceCodes(List<String> secondaryResourceCodes) {
            this.secondaryResourceCodes = secondaryResourceCodes;
        }

        public OffsetDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(OffsetDateTime startTime) {
            this.startTime = startTime;
        }

        public OffsetDateTime getLockFrom() {
            return lockFrom;
        }

        public void setLockFrom(OffsetDateTime lockFrom) {
            this.lockFrom = lockFrom;
        }

        public OffsetDateTime getLockTo() {
            return lockTo;
        }

        public void setLockTo(OffsetDateTime lockTo) {
            this.lockTo = lockTo;
        }

        public List<String> getLockedResourceCodes() {
            return lockedResourceCodes;
        }

        public void setLockedResourceCodes(List<String> lockedResourceCodes) {
            this.lockedResourceCodes = lockedResourceCodes;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public Boolean getDryRun() {
            return dryRun;
        }

        public void setDryRun(Boolean dryRun) {
            this.dryRun = dryRun;
        }

        public List<BigDecimal> getSplitQuantities() {
            return splitQuantities;
        }

        public void setSplitQuantities(List<BigDecimal> splitQuantities) {
            this.splitQuantities = splitQuantities;
        }

        public List<Long> getMergeTaskIds() {
            return mergeTaskIds;
        }

        public void setMergeTaskIds(List<Long> mergeTaskIds) {
            this.mergeTaskIds = mergeTaskIds;
        }
    }

    /**
     * 调整结果（C22 {@code TaskAdjustResult}，C36 追加 created/removed）。
     *
     * @param adjustmentId 调整记录主键；dryRun 为空
     * @param applied 是否已应用（dryRun 表示校验通过）
     * @param dryRun 是否仅校验
     * @param task 调整后的任务视图
     * @param conflicts 冲突列表，成功时为空
     * @param createdTaskIds SPLIT 新建任务主键；dryRun 或非 SPLIT 为空
     * @param removedTaskIds MERGE 软删任务主键；dryRun 或非 MERGE 为空
     */
    public record TaskAdjustResult(Long adjustmentId, boolean applied, boolean dryRun, TaskView task,
                                   List<TaskConflict> conflicts, List<Long> createdTaskIds,
                                   List<Long> removedTaskIds) {
    }

    /**
     * 冲突项。
     *
     * @param type 冲突类型
     * @param message 说明
     * @param relatedTaskIds 关联任务主键
     */
    public record TaskConflict(String type, String message, List<Long> relatedTaskIds) {
    }

    /**
     * 任务视图。
     *
     * @param resourceCode 主资源编码
     * @param status 任务状态
     * @param version 版本
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param lockType 锁类型
     * @param lockedFrom 锁窗开始
     * @param lockedTo 锁窗结束
     * @param lockedResourceCodes 锁定资源编码
     */
    public record TaskView(String resourceCode, String status, int version,
                           OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                           OffsetDateTime startTime, OffsetDateTime endTime, String lockType,
                           OffsetDateTime lockedFrom, OffsetDateTime lockedTo,
                           List<String> lockedResourceCodes) {
    }

    /**
     * 调整历史项。
     *
     * @param adjustmentId 调整记录主键
     * @param adjustType 调整类型
     * @param before 调整前快照
     * @param after 调整后快照
     * @param reason 原因
     * @param undone 是否已撤销
     * @param createdAt 创建时间
     */
    public record TaskAdjustmentView(Long adjustmentId, String adjustType,
                                     Map<String, Object> before, Map<String, Object> after,
                                     String reason, boolean undone, OffsetDateTime createdAt) {
    }

    /**
     * 调整历史分页。
     *
     * @param items 当前页
     * @param total 总条数
     * @param page 页码
     * @param size 页大小
     */
    public record AdjustmentPage(List<TaskAdjustmentView> items, int total, int page, int size) {
    }
}
