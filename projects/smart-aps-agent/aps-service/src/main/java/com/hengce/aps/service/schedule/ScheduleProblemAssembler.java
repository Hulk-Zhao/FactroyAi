package com.hengce.aps.service.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingEdgeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingNodeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.OperationTemplateMapper;
import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingEdge;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroup;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.FrozenTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.CompletedFrozenOperationRow;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDsl;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDslMapper;
import com.hengce.aps.service.setup.SetupMatrixService;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ConstraintSpec;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.duration.OperationRunDuration;
import com.hengce.aps.solver.dsl.ConstraintDslEngineMapping;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 求解问题装配：把 DB 中的订单、ACTIVE 工艺路线、资源、日历与冻结任务映射为 {@link ScheduleProblem}。
 *
 * <p>数据来源遵循 BD-03/04/05/06/07 的读取约定：{@code routing.nodes/edges} JSONB 只是画布快照，
 * 事实来源是 {@code routing_node}/{@code routing_edge}。跨域主数据只读复用既有 Mapper，
 * 不复制对方的实体。</p>
 */
@Service
public class ScheduleProblemAssembler {

    /**
     * 求解窗口日志：锚点生效时 {@code horizonStart} 不再随真实时刻漂移，供复现性核对。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleProblemAssembler.class);

    /**
     * 可用时段向前后各扩展的小时数，覆盖求解器以自身 now 为基准的 7 天 horizon。
     */
    private static final int HORIZON_MARGIN_HOURS = 2;

    /**
     * 求解器 horizon 小时数，与 TimefoldModelBuilder 保持一致。
     */
    private static final int HORIZON_HOURS = 168;

    /**
     * 路线分页查询页大小。
     */
    private static final int ROUTING_PAGE_SIZE = 5;

    /**
     * 日历分页查询页大小。
     */
    private static final int CALENDAR_PAGE_SIZE = 50;

    /**
     * 支持的依赖类型。
     */
    private static final List<String> SUPPORTED_DEPEND_TYPES = List.of("FS", "SS", "FF");

    /**
     * 排产持久层。
     */
    private final ScheduleRepository scheduleRepository;

    /**
     * 工艺路线 Mapper。
     */
    private final RoutingMapper routingMapper;

    /**
     * 工艺路线节点 Mapper。
     */
    private final RoutingNodeMapper routingNodeMapper;

    /**
     * 工艺路线依赖边 Mapper。
     */
    private final RoutingEdgeMapper routingEdgeMapper;

    /**
     * 工序模版 Mapper。
     */
    private final OperationTemplateMapper operationTemplateMapper;

    /**
     * 资源 Mapper。
     */
    private final ResourceMapper resourceMapper;

    /**
     * 资源组 Mapper。
     */
    private final ResourceGroupMapper resourceGroupMapper;

    /**
     * 日历 Mapper。
     */
    private final CalendarMapper calendarMapper;

    /**
     * 换型矩阵服务：装配时装载 FROM/TO 矩阵。
     */
    private final SetupMatrixService setupMatrixService;

    /**
     * 约束 DSL 持久层；为 null 时（装配器单测）视为没有 ACTIVE DSL。
     */
    private final ConstraintDslMapper constraintDslMapper;

    /**
     * 业务时区（{@code aps.business.time-zone}）：日历班次的本地时间按此时区落地。
     */
    private final ZoneId businessZone;

    /**
     * 构造装配器（兼容不含约束 DSL 依赖的单测）。
     *
     * @param scheduleRepository 排产持久层
     * @param routingMapper 路线 Mapper
     * @param routingNodeMapper 节点 Mapper
     * @param routingEdgeMapper 边 Mapper
     * @param operationTemplateMapper 模版 Mapper
     * @param resourceMapper 资源 Mapper
     * @param resourceGroupMapper 资源组 Mapper
     * @param calendarMapper 日历 Mapper
     * @param setupMatrixService 换型矩阵服务
     * @param businessTimeZone 业务时区，默认 Asia/Shanghai
     */
    public ScheduleProblemAssembler(
            ScheduleRepository scheduleRepository,
            RoutingMapper routingMapper,
            RoutingNodeMapper routingNodeMapper,
            RoutingEdgeMapper routingEdgeMapper,
            OperationTemplateMapper operationTemplateMapper,
            ResourceMapper resourceMapper,
            ResourceGroupMapper resourceGroupMapper,
            CalendarMapper calendarMapper,
            SetupMatrixService setupMatrixService,
            @Value("${aps.business.time-zone:Asia/Shanghai}") String businessTimeZone) {
        this(scheduleRepository, routingMapper, routingNodeMapper, routingEdgeMapper,
                operationTemplateMapper, resourceMapper, resourceGroupMapper, calendarMapper,
                setupMatrixService, businessTimeZone, null);
    }

    /**
     * 构造装配器。
     *
     * @param scheduleRepository 排产持久层
     * @param routingMapper 路线 Mapper
     * @param routingNodeMapper 节点 Mapper
     * @param routingEdgeMapper 边 Mapper
     * @param operationTemplateMapper 模版 Mapper
     * @param resourceMapper 资源 Mapper
     * @param resourceGroupMapper 资源组 Mapper
     * @param calendarMapper 日历 Mapper
     * @param setupMatrixService 换型矩阵服务
     * @param businessTimeZone 业务时区，默认 Asia/Shanghai
     * @param constraintDslMapper 约束 DSL Mapper
     */
    @Autowired
    public ScheduleProblemAssembler(
            ScheduleRepository scheduleRepository,
            RoutingMapper routingMapper,
            RoutingNodeMapper routingNodeMapper,
            RoutingEdgeMapper routingEdgeMapper,
            OperationTemplateMapper operationTemplateMapper,
            ResourceMapper resourceMapper,
            ResourceGroupMapper resourceGroupMapper,
            CalendarMapper calendarMapper,
            SetupMatrixService setupMatrixService,
            @Value("${aps.business.time-zone:Asia/Shanghai}") String businessTimeZone,
            ConstraintDslMapper constraintDslMapper) {
        this.scheduleRepository = scheduleRepository;
        this.routingMapper = routingMapper;
        this.routingNodeMapper = routingNodeMapper;
        this.routingEdgeMapper = routingEdgeMapper;
        this.operationTemplateMapper = operationTemplateMapper;
        this.resourceMapper = resourceMapper;
        this.resourceGroupMapper = resourceGroupMapper;
        this.calendarMapper = calendarMapper;
        this.setupMatrixService = setupMatrixService;
        this.businessZone = ZoneId.of(businessTimeZone.trim());
        this.constraintDslMapper = constraintDslMapper;
    }

    /**
     * 增量重排参数：需要以既有任务固定进题面的订单号（未受影响订单）。
     */
    public static final String PARAM_PINNED_ORDER_NOS = "pinnedOrderNos";

    /**
     * 求解参数：是否用既有排产结果作为预热初始解；缺省 {@code true}。
     */
    public static final String PARAM_WARM_START = "warmStart";

    /**
     * 求解基准时间锚点（{@code aps.solve.horizon-anchor}，ISO-8601）；空表示取当前时刻。
     */
    private volatile String horizonAnchor = "";

    /**
     * 配置求解基准时间锚点：给定后同一份数据在不同真实时刻得到同一题面。
     *
     * <p>只允许“过去或现在”：未来锚点会让计划的“现在线”落在真实未来，
     * 当前至锚点之间已排的既有任务会被窗口过滤掉，产出的计划不可执行。</p>
     *
     * @param horizonAnchor ISO-8601 时刻，空表示取当前时刻
     */
    @Value("${aps.solve.horizon-anchor:}")
    public void setHorizonAnchor(String horizonAnchor) {
        this.horizonAnchor = horizonAnchor == null ? "" : horizonAnchor.trim();
    }

    /**
     * ATP 假设订单描述。
     *
     * @param orderId 订单主键（无既有订单时为临时 UUID）
     * @param orderNo 订单号（无既有订单时为 ATP- 前缀临时号）
     * @param materialCode 物料编码
     * @param productFamily 产品族，可空
     * @param qty 数量
     * @param dueDate 请求交期
     * @param priority 优先级
     * @param routingId 指定工艺路线，可空（空则取 ACTIVE 最新）
     */
    public record AtpOrderSpec(
            UUID orderId,
            String orderNo,
            String materialCode,
            String productFamily,
            BigDecimal qty,
            LocalDate dueDate,
            String priority,
            UUID routingId) {
    }

    /**
     * ATP 只读题面快照。
     *
     * @param tenantId 租户
     * @param sandboxId 场景（恒为 BASELINE，仅读取）
     * @param problem 求解问题
     * @param atpOrder 假设订单；无可用资源时为 null
     * @param horizonStart 资源日历展开起点
     * @param horizonEnd 资源日历展开终点
     * @param confirmedOrderCount 参与的 CONFIRMED 订单数（FAST 为 0）
     */
    public record AtpSnapshot(
            UUID tenantId,
            UUID sandboxId,
            ScheduleProblem problem,
            SchedulingOrder atpOrder,
            OffsetDateTime horizonStart,
            OffsetDateTime horizonEnd,
            int confirmedOrderCount) {
    }


    /**
     * 从数据库装配求解问题。
     *
     * @param request 求解请求
     * @param config 求解配置
     * @return 装配结果，含问题快照、参与订单与降级信息
     */
    public AssembledProblem assemble(SolveRequest request, SolveConfig config) {
        UUID tenantId = TenantContext.getTenantId();
        UUID sandboxId = request.sandboxId() == null
                ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId();
        OffsetDateTime base = resolveBaseTime();
        OffsetDateTime horizonStart = base.minusHours(HORIZON_MARGIN_HOURS);
        OffsetDateTime horizonEnd = horizonStart.plusHours(HORIZON_HOURS + 2 * HORIZON_MARGIN_HOURS);

        LOGGER.info("求解窗口: base={}, horizonStart={}, horizonEnd={}, anchor={}",
                base, horizonStart, horizonEnd,
                horizonAnchor == null || horizonAnchor.isBlank() ? "<now>" : horizonAnchor);
        boolean allOrders = request.orderNos() == null || request.orderNos().isEmpty();
        List<OrderSourceRow> requestedRows = scheduleRepository.findOrders(
                tenantId, request.orderNos(), allOrders);
        requireAllRequestedOrdersFound(request.orderNos(), requestedRows);
        List<OrderSourceRow> requestedOrderRows = requestedRows;
        List<OrderSourceRow> pinnedRows = new ArrayList<>();
        if (!allOrders) {
            Map<String, Object> parameters = request.parameters();
            Object pinnedParam = parameters == null ? null : parameters.get(PARAM_PINNED_ORDER_NOS);
            pinnedRows.addAll(findPinnedOrders(tenantId, request.orderNos(), readStringList(pinnedParam)));
        }
        // 同场景内不在本次请求范围内的既有任务同样是“既有占用”，必须作为不可移动的
        // pinned 项装入题面；否则求解器看不见别的订单已占用的资源与时段，会排出落库后
        // 整表重叠的结果（C21 §24.3：装载范围与校验范围必须一致）。
        Set<UUID> pinnedOrderIds = new LinkedHashSet<>();
        for (OrderSourceRow row : pinnedRows) {
            pinnedOrderIds.add(row.orderId());
        }
        for (OrderSourceRow row : scheduleRepository.findOrdersWithExistingTasks(
                tenantId, sandboxId,
                requestedOrderRows.stream().map(OrderSourceRow::orderId).toList(),
                horizonStart, horizonEnd)) {
            if (pinnedOrderIds.add(row.orderId())) {
                pinnedRows.add(row);
            }
        }
        List<OrderSourceRow> orderRows = new ArrayList<>(requestedOrderRows);
        orderRows.addAll(pinnedRows);
        ActiveConstraintDsl activeDsl = loadActiveConstraintDsl();
        List<ConstraintSpec> constraints = activeDsl == null ? List.of() : activeDsl.constraints();
        if (orderRows.isEmpty()) {
            return new AssembledProblem(tenantId, sandboxId, new ScheduleProblem(
                    tenantId.toString(), sandboxId, null, request.strategyId(), config.direction(),
                    List.of(), List.of(), List.of(), constraints, List.of(),
                    toUtcLocal(base), List.of()), List.of(), activeDsl);
        }
        Map<UUID, CompletedFrozenOps> completedByOrder =
                loadCompletedFrozenOperations(tenantId, sandboxId, horizonStart);

        List<SchedulingResource> resources = buildResources(horizonStart, horizonEnd);
        Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();
        for (SchedulingResource resource : resources) {
            resourceById.put(resource.resourceId(), resource);
        }
        if (resourceById.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY, "租户下没有可用资源，无法排产");
        }

        Map<String, OperationTemplate> templateCache = new HashMap<>();
        Map<String, List<RoutingNode>> nodeCache = new HashMap<>();
        Set<UUID> requestedOrderIds = new LinkedHashSet<>();
        for (OrderSourceRow order : requestedOrderRows) {
            requestedOrderIds.add(order.orderId());
        }
        List<SchedulingOrder> orders = new ArrayList<>();
        List<UUID> solvedOrderIds = new ArrayList<>();
        for (OrderSourceRow order : orderRows) {
            boolean requested = requestedOrderIds.contains(order.orderId());
            CompletedFrozenOps completed = requested ? completedByOrder.get(order.orderId()) : null;
            SchedulingOrder schedulingOrder = toSchedulingOrder(order, resolveRouting(order),
                    templateCache, nodeCache, resourceById, completed);
            if (schedulingOrder == null) {
                // 全部工序都已在窗口外完成：该订单本次无工序可排，保留库内既有任务不删，
                // 也不进入写入范围（orderIds），避免把已完成任务当作本次求解结果覆盖掉。
                continue;
            }
            orders.add(schedulingOrder);
            if (requested) {
                // pinned 订单只作为约束参与题面，落库范围必须与请求订单一致。
                solvedOrderIds.add(order.orderId());
            }
        }

        List<TaskAssignment> pinnedAssignments = buildFrozenAssignments(
                scheduleRepository.findTasksByOrders(tenantId, sandboxId,
                        pinnedRows.stream().map(OrderSourceRow::orderId).toList()), true);
        List<TaskAssignment> frozenAssignments = mergeAssignments(
                pinnedAssignments,
                buildFrozenAssignments(scheduleRepository.findFrozenTasks(
                        tenantId, sandboxId, horizonStart, horizonEnd), false));
        validateFloatOperationDurations(orders, resourceById, frozenAssignments);
        List<SetupMatrixEntry> setupMatrix = loadSetupMatrix();
        List<TaskAssignment> initialAssignments = warmStartEnabled(config.parameters())
                ? buildFrozenAssignments(scheduleRepository.findTasksByOrders(tenantId, sandboxId,
                        requestedOrderRows.stream().map(OrderSourceRow::orderId).toList()), false)
                : List.of();

        ScheduleProblem problem = new ScheduleProblem(
                tenantId.toString(),
                sandboxId,
                null,
                request.strategyId(),
                config.direction(),
                orders,
                resources,
                frozenAssignments,
                constraints,
                setupMatrix,
                toUtcLocal(base),
                initialAssignments);
        return new AssembledProblem(tenantId, sandboxId, problem, solvedOrderIds, activeDsl);
    }

    /**
     * 计算求解基准时间：配置了 {@code aps.solve.horizon-anchor} 时取锚点，否则取当前时刻；
     * 两者都向下取整到小时，保证装配侧与求解器的开始时间候选同源。
     *
     * <p>拒绝未来锚点：未来锚点会把“现在”线画到真实未来，当前至锚点之间已排的既有任务
     * 会被窗口过滤掉，产出的计划不可执行。</p>
     *
     * @return 基准时间（UTC，整点）
     */
    OffsetDateTime resolveBaseTime() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (horizonAnchor == null || horizonAnchor.isBlank()) {
            return now.withMinute(0).withSecond(0).withNano(0);
        }
        OffsetDateTime anchor;
        try {
            anchor = OffsetDateTime.parse(horizonAnchor);
        } catch (DateTimeParseException exception) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "aps.solve.horizon-anchor 不是合法的 ISO-8601 时刻: " + horizonAnchor);
        }
        if (anchor.isAfter(now)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "aps.solve.horizon-anchor 不允许晚于当前时刻（会画错“现在”线）: " + horizonAnchor);
        }
        return anchor.withOffsetSameInstant(ZoneOffset.UTC)
                .withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * 读取窗口外已完成的 FROZEN 工序：用于把对应工序移出题面，并为剩余工序提供订单级下界。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param horizonStart 求解 horizon 起点
     * @return 订单主键到已完成工序集合与最大结束时间
     */
    private Map<UUID, CompletedFrozenOps> loadCompletedFrozenOperations(
            UUID tenantId, UUID sandboxId, OffsetDateTime horizonStart) {
        Map<UUID, Set<Integer>> indicesByOrder = new LinkedHashMap<>();
        Map<UUID, OffsetDateTime> maxEndByOrder = new LinkedHashMap<>();
        for (CompletedFrozenOperationRow row
                : scheduleRepository.findFrozenOperationsBeforeWindow(tenantId, sandboxId, horizonStart)) {
            if (row.orderId() == null) {
                continue;
            }
            indicesByOrder.computeIfAbsent(row.orderId(), key -> new LinkedHashSet<>())
                    .add(row.operationIndex());
            if (row.maxEndTime() != null) {
                maxEndByOrder.merge(row.orderId(), row.maxEndTime(),
                        (left, right) -> left.isAfter(right) ? left : right);
            }
        }
        Map<UUID, CompletedFrozenOps> result = new LinkedHashMap<>();
        indicesByOrder.forEach((orderId, indices) -> result.put(
                orderId, new CompletedFrozenOps(Set.copyOf(indices), maxEndByOrder.get(orderId))));
        return result;
    }

    /**
     * 是否启用预热初始解；请求参数 {@code warmStart} 缺省为 {@code true}。
     *
     * @param parameters 求解参数，可空
     * @return 是否启用
     */
    private static boolean warmStartEnabled(Map<String, Object> parameters) {
        Object value = parameters == null ? null : parameters.get(PARAM_WARM_START);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return true;
    }

    private static LocalDateTime toUtcLocal(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 读取当前租户唯一 ACTIVE 的约束 DSL 并映射为引擎约束快照。
     *
     * <p>不能“挑一条最像的”静默继续：同一租户存在多条 ACTIVE（不同 code）时，
     * 本次求解到底用哪套权重无法确定，直接以 42200 拒绝并点名全部 code；
     * 激活行里出现引擎无法忠实执行的规则/参数时同样以 42200 拒绝并点名规则，
     * 避免用户以为约束已生效而排产实际未变化。</p>
     *
     * @return 唯一 ACTIVE 的 DSL 快照；没有 ACTIVE 时返回 null
     */
    private ActiveConstraintDsl loadActiveConstraintDsl() {
        if (constraintDslMapper == null) {
            return null;
        }
        List<ConstraintDsl> actives = constraintDslMapper.selectList(
                Wrappers.<ConstraintDsl>lambdaQuery().eq(ConstraintDsl::getStatus, "ACTIVE"));
        if (actives == null || actives.isEmpty()) {
            return null;
        }
        if (actives.size() > 1) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "当前租户存在 " + actives.size() + " 条 ACTIVE 约束 DSL（code="
                            + actives.stream().map(ConstraintDsl::getCode).sorted().toList()
                            + "），无法确定本次求解使用哪一条；请只保留一条 ACTIVE 后重试");
        }
        ConstraintDsl active = actives.get(0);
        List<ConstraintSpec> specs;
        try {
            specs = ConstraintDslEngineMapping.fromCompiledSpec(active.compiledSpecCopy());
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "ACTIVE 约束 DSL code=" + active.getCode() + " v" + active.getVersionNo()
                            + " 的 compiled_spec 无法映射到求解引擎: " + exception.getMessage());
        }
        ConstraintDslEngineMapping.Analysis analysis = ConstraintDslEngineMapping.analyze(specs);
        if (!analysis.supported()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "ACTIVE 约束 DSL code=" + active.getCode() + " v" + active.getVersionNo()
                            + " 含求解引擎不支持的规则/参数，已拒绝求解: "
                            + String.join("；", analysis.problems()));
        }
        return new ActiveConstraintDsl(
                (UUID) active.getId(),
                active.getCode(),
                active.getVersionNo() == null ? 0 : active.getVersionNo(),
                active.getChecksum(),
                specs,
                analysis.weights());
    }

    /**
     * 装载租户下全部换型矩阵条目；装配器单测可不注入该依赖。
     *
     * @return 矩阵条目；未注入服务时为空列表
     */
    private List<SetupMatrixEntry> loadSetupMatrix() {
        return setupMatrixService == null ? List.of() : setupMatrixService.loadForSolver();
    }

    /**
     * 校验 {@code pinnedOrderNos} 与 {@code orderNos} 无交集，并返回去重后的 pin 订单号。
     *
     * <p>输入自相矛盾时不能自己挑一个解释继续跑：同一订单既要求“钉住不动”又要求“参与重排”，
     * 静默剔除只会让调用方以为 pin 生效，实测会让整组对照结果失效。这里直接以 40000 拒绝，
     * 并在 message 中指名重叠订单。</p>
     *
     * @param requestedOrderNos 参与求解的订单号
     * @param pinnedOrderNos 需要 pin 的订单号
     * @return 去重后的 pin 订单号；无 pin 时返回空列表
     */
    static List<String> requireDisjointPinnedOrders(
            List<String> requestedOrderNos, List<String> pinnedOrderNos) {
        List<String> pinned = pinnedOrderNos == null ? List.of() : pinnedOrderNos;
        if (pinned.isEmpty()) {
            return List.of();
        }
        Set<String> requested = new HashSet<>(requestedOrderNos == null ? List.of() : requestedOrderNos);
        Set<String> overlap = new LinkedHashSet<>(pinned);
        overlap.retainAll(requested);
        if (!overlap.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "pinnedOrderNos 与 orderNos 存在重叠订单: " + String.join("、", overlap)
                            + "；钉住的订单不能同时作为重排目标");
        }
        return pinned.stream().distinct().toList();
    }

    /**
     * 受理前的订单存在性校验：请求里显式点名但库里查不到的订单号必须 400 拒绝，
     * 不能静默漏排后照常返回 SOLVED。
     *
     * <p>与 {@link #requireDisjointPinnedOrders} 是两件事：那条管“同一订单既 pin 又重排”的自相矛盾，
     * 这条管“调用方点名的订单被悄悄丢掉”。口径按租户：{@code orders} 表没有 {@code sandbox_id}，
     * 订单不按场景隔离，因此不存在“订单不在该场景范围”这一维度；存在性判断不看 {@code status}
     * （显式点名时既有行为本就不按状态过滤）。但终态取消是“这活不干了”的否决语义，显式点名
     * 也不能覆盖：查到的订单里只要有一个 {@code CANCELLED} 即拒绝，见
     * {@link #requireNoCancelledOrderNos}。</p>
     *
     * <p>放在提交入口而不是只放在装配阶段，是为了在异步模式下也能直接返回 400，
     * 而不是先受理成 PENDING、再在后台降级成 FAILED。</p>
     *
     * @param request 求解请求
     */
    public void requireExistingOrderNos(SolveRequest request) {
        if (request.orderNos() == null || request.orderNos().isEmpty()) {
            return;
        }
        List<OrderSourceRow> requestedRows = scheduleRepository.findOrders(
                TenantContext.getTenantId(), request.orderNos(), false);
        requireAllRequestedOrdersFound(request.orderNos(), requestedRows);
        requireNoCancelledOrderNos(request.orderNos(), requestedRows);
    }

    /**
     * 计算本次求解实际覆盖的订单号：显式点名时取点名集合，缺省时取场景内全部已确认订单。
     *
     * <p>该结果写入 {@code solve_task.config.orderNos}，供后续增量重排判定“当前版本”的订单范围，
     * 避免缺省增量把同一场景内其他客户/批次的订单纳入重排。</p>
     *
     * @param request 求解请求
     * @return 实际参与求解的订单号（保持读取顺序并去重）
     */
    public List<String> effectiveOrderNos(SolveRequest request) {
        boolean allOrders = request.orderNos() == null || request.orderNos().isEmpty();
        return scheduleRepository.findOrders(TenantContext.getTenantId(), request.orderNos(), allOrders)
                .stream()
                .map(OrderSourceRow::orderNo)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 校验请求点名的订单号都能在读取结果里找到；缺任何一个都 400，并在 message 中逐字点名。
     *
     * @param requestedOrderNos 请求点名的订单号
     * @param foundRows 实际读取到的订单行
     * @throws BizException 存在查不到的订单号时抛出 {@code 400/40000}
     */
    static void requireAllRequestedOrdersFound(List<String> requestedOrderNos, List<OrderSourceRow> foundRows) {
        if (requestedOrderNos == null || requestedOrderNos.isEmpty()) {
            return;
        }
        Set<String> found = foundRows.stream()
                .map(OrderSourceRow::orderNo)
                .collect(Collectors.toSet());
        List<String> missing = requestedOrderNos.stream()
                .filter(orderNo -> orderNo == null || !found.contains(orderNo))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "请求的订单号不存在: " + String.join("、",
                            missing.stream().map(orderNo -> orderNo == null ? "<空>" : orderNo).toList()));
        }
    }

    /**
     * 校验请求点名的订单里没有终态 {@code CANCELLED}；有则 400 并逐字点名。
     *
     * <p>只拦 {@code CANCELLED}：{@code DRAFT/PLANNED/IN_PROGRESS/COMPLETED} 的显式点名是既有语义，
     * 其中在制/完工的重排由增量与调整链路负责，不在受理入口否决。</p>
     *
     * <p>调用顺序固定在 {@link #requireAllRequestedOrdersFound} 之后：混合输入（1 个不存在 +
     * 1 个已取消）只报“不存在”，因为查无此单时无从判断状态。</p>
     *
     * @param requestedOrderNos 请求点名的订单号
     * @param foundRows 实际读取到的订单行
     * @throws BizException 存在已取消订单时抛出 {@code 400/40000}
     */
    static void requireNoCancelledOrderNos(List<String> requestedOrderNos, List<OrderSourceRow> foundRows) {
        if (requestedOrderNos == null || requestedOrderNos.isEmpty()) {
            return;
        }
        Set<String> cancelled = foundRows.stream()
                .filter(row -> "CANCELLED".equals(row.status()))
                .map(OrderSourceRow::orderNo)
                .collect(Collectors.toSet());
        List<String> blocked = requestedOrderNos.stream()
                .filter(orderNo -> orderNo != null && cancelled.contains(orderNo))
                .distinct()
                .toList();
        if (!blocked.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "请求的订单号已取消，不能排产: " + String.join("、", blocked));
        }
    }

    /**
     * 受理前的参数自洽性校验：从 {@code parameters} 读取 {@code pinnedOrderNos} 并检查交集。
     *
     * <p>放在提交入口而不是只放在装配阶段，是为了在异步模式下也能直接返回 400，
     * 而不是先受理成 PENDING、再在后台降级成 FAILED。</p>
     *
     * @param requestedOrderNos 参与求解的订单号
     * @param parameters 求解参数，可空
     */
    public static void validatePinnedOrderNos(
            List<String> requestedOrderNos, Map<String, Object> parameters) {
        requireDisjointPinnedOrders(requestedOrderNos,
                readStringList(parameters == null ? null : parameters.get(PARAM_PINNED_ORDER_NOS)));
    }

    /**
     * 读取需要 pin 进题面的未受影响订单；与求解集合重叠时拒绝求解。
     *
     * @param tenantId 租户标识
     * @param requestedOrderNos 参与求解的订单号
     * @param pinnedOrderNos 需要 pin 的订单号
     * @return 去重后的订单行
     */
    private List<OrderSourceRow> findPinnedOrders(
            UUID tenantId, List<String> requestedOrderNos, List<String> pinnedOrderNos) {
        List<String> filtered = requireDisjointPinnedOrders(requestedOrderNos, pinnedOrderNos);
        if (filtered.isEmpty()) {
            return List.of();
        }
        return scheduleRepository.findOrders(tenantId, filtered, false);
    }

    /**
     * 解析请求参数中的订单号列表。
     *
     * @param value 参数值
     * @return 订单号列表，空值返回空列表
     */
    @SuppressWarnings("unchecked")
    private static List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * 合并分配：pinned 优先，按 taskId 去重。
     *
     * @param pinned 固定分配
     * @param frozen 锁定分配
     * @return 合并后的分配
     */
    private static List<TaskAssignment> mergeAssignments(
            List<TaskAssignment> pinned, List<TaskAssignment> frozen) {
        Map<Long, TaskAssignment> merged = new LinkedHashMap<>();
        for (TaskAssignment assignment : pinned) {
            merged.put(assignment.taskId(), assignment);
        }
        for (TaskAssignment assignment : frozen) {
            merged.putIfAbsent(assignment.taskId(), assignment);
        }
        return List.copyOf(merged.values());
    }

    private Routing resolveRouting(OrderSourceRow order) {
        if (order.routingId() != null) {
            Routing routing = routingMapper.selectById(order.routingId());
            if (routing != null) {
                return routing;
            }
        }
        List<Routing> actives = routingMapper.selectPageByFilter(
                new Page<>(1, ROUTING_PAGE_SIZE), order.materialCode(), null, "ACTIVE",
                "effective_date", "DESC")
                .getRecords();
        if (actives.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "订单 " + order.orderNo() + " 的产品 " + order.materialCode() + " 没有 ACTIVE 工艺路线");
        }
        return actives.get(0);
    }

    /**
     * 把订单行与工艺路线装配为求解器订单快照。
     *
     * @param order 订单来源行
     * @param routing 已解析的工艺路线
     * @param templateCache 工序模版缓存
     * @param nodeCache 路线节点缓存
     * @param resourceById 场景资源
     * @return 订单快照
     */
    private SchedulingOrder toSchedulingOrder(
            OrderSourceRow order, Routing routing, Map<String, OperationTemplate> templateCache,
            Map<String, List<RoutingNode>> nodeCache, Map<UUID, SchedulingResource> resourceById) {
        return toSchedulingOrder(order, routing, templateCache, nodeCache, resourceById, null);
    }

    private SchedulingOrder toSchedulingOrder(
            OrderSourceRow order, Routing routing, Map<String, OperationTemplate> templateCache,
            Map<String, List<RoutingNode>> nodeCache, Map<UUID, SchedulingResource> resourceById,
            CompletedFrozenOps completed) {
        UUID routingId = requireUuid(routing.getId());
        List<RoutingNode> nodes = nodeCache.computeIfAbsent(
                routingId.toString(), key -> routingNodeMapper.selectByRoutingId(routingId));
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "订单 " + order.orderNo() + " 的 ACTIVE 工艺路线没有节点");
        }
        List<RoutingEdge> edges = routingEdgeMapper.selectByRoutingId(routingId);
        Map<UUID, Integer> seqByNodeId = new HashMap<>();
        for (RoutingNode node : nodes) {
            seqByNodeId.put(requireUuid(node.getId()), node.getSeqNo());
        }
        Map<UUID, List<OperationDependency>> dependenciesByNode =
                dependencies(edges, seqByNodeId, order.orderNo());
        Set<Integer> completedIndices = completed == null
                ? Set.of() : completed.operationIndices();
        if (!completedIndices.isEmpty()) {
            // 窗口外已完成的 FROZEN 工序视为已完工事实，移出题面；剩余工序不得早于其结束时间。
            // 同时剔除指向已完工工序的前序依赖，否则依赖校验会报“前序工序不存在”；
            // 剔空后求解侧按 operationIndex 串行补默认 FS，语义仍正确。
            dependenciesByNode.replaceAll((nodeId, deps) -> deps.stream()
                    .filter(dependency ->
                            !completedIndices.contains(dependency.predecessorOperationIndex()))
                    .toList());
        }
        List<SchedulingOperation> operations = new ArrayList<>();
        for (RoutingNode node : nodes) {
            if (completedIndices.contains(node.getSeqNo())) {
                continue;
            }
            operations.add(toOperation(node, templateCache,
                    dependenciesByNode.get(requireUuid(node.getId())), resourceById));
        }
        if (operations.isEmpty()) {
            return null;
        }
        return new SchedulingOrder(
                order.orderId(),
                order.orderNo(),
                order.materialCode(),
                order.productFamily(),
                order.qty(),
                order.dueDate(),
                order.priority(),
                order.freezeStatus(),
                routingId,
                operations,
                completed == null ? null : toUtcLocal(completed.maxEndTime()));
    }

    /**
     * ATP 只读题面装配：在 CONFIRMED 订单题面上追加一个“假设订单”，全程不写库。
     *
     * <p>{@code includeConfirmedOrders=false} 时只装配假设订单与资源/日历，供 FAST 使用；
     * {@code true} 时与主排产同口径（CONFIRMED 订单 + 冻结任务），供 ACCURATE 限时求解。</p>
     *
     * <p>无可用资源时不抛异常，返回 {@code atpOrder=null} 的探针，让调用方按
     * {@code INFEASIBLE} 返回“无可用资源”，而不是把只读查询变成 500/422。</p>
     *
     * @param spec 假设订单描述
     * @param horizonHours 资源日历展开 horizon 小时数
     * @param includeConfirmedOrders 是否包含 CONFIRMED 订单题面
     * @return 只读题面快照
     */
    public AtpSnapshot assembleAtp(AtpOrderSpec spec, int horizonHours, boolean includeConfirmedOrders) {
        UUID tenantId = TenantContext.getTenantId();
        UUID sandboxId = ScheduleProblem.DEFAULT_SANDBOX_ID;
        OffsetDateTime base = resolveBaseTime();
        OffsetDateTime horizonStart = base.minusHours(HORIZON_MARGIN_HOURS);
        OffsetDateTime horizonEnd = horizonStart.plusHours(horizonHours);
        List<SchedulingResource> resources = buildResources(horizonStart, horizonEnd);
        if (resources.isEmpty()) {
            return new AtpSnapshot(tenantId, sandboxId, new ScheduleProblem(
                    tenantId.toString(), sandboxId, null, null, "FORWARD",
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    toUtcLocal(base), List.of()),
                    null, horizonStart, horizonEnd, 0);
        }
        Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();
        for (SchedulingResource resource : resources) {
            resourceById.put(resource.resourceId(), resource);
        }
        Map<String, OperationTemplate> templateCache = new HashMap<>();
        Map<String, List<RoutingNode>> nodeCache = new HashMap<>();
        List<SchedulingOrder> orders = new ArrayList<>();
        int confirmedOrderCount = 0;
        if (includeConfirmedOrders) {
            for (OrderSourceRow row : scheduleRepository.findOrders(tenantId, null, true)) {
                if (spec.orderNo() != null && spec.orderNo().equals(row.orderNo())) {
                    continue;
                }
                orders.add(toSchedulingOrder(row, resolveRouting(row), templateCache, nodeCache, resourceById));
                confirmedOrderCount++;
            }
        }
        Routing routing = resolveAtpRouting(spec);
        SchedulingOrder atpOrder = toSchedulingOrder(
                new OrderSourceRow(spec.orderId(), spec.orderNo(), null, spec.materialCode(),
                        spec.productFamily(), spec.qty(), spec.dueDate(), spec.priority(),
                        "UNFROZEN", spec.routingId(), "ATP"),
                routing, templateCache, nodeCache, resourceById);
        orders.add(atpOrder);
        List<TaskAssignment> frozenAssignments = includeConfirmedOrders
                ? buildFrozenAssignments(scheduleRepository.findFrozenTasks(
                        tenantId, sandboxId, horizonStart, horizonEnd), false)
                : List.of();
        ScheduleProblem problem = new ScheduleProblem(
                tenantId.toString(), sandboxId, null, null, "FORWARD",
                orders, resources, frozenAssignments, List.of(), loadSetupMatrix(),
                toUtcLocal(base), List.of());
        return new AtpSnapshot(tenantId, sandboxId, problem, atpOrder,
                horizonStart, horizonEnd, confirmedOrderCount);
    }

    private Routing resolveAtpRouting(AtpOrderSpec spec) {
        if (spec.routingId() != null) {
            Routing routing = routingMapper.selectById(spec.routingId());
            if (routing != null) {
                return routing;
            }
        }
        List<Routing> actives = routingMapper.selectPageByFilter(
                new Page<>(1, ROUTING_PAGE_SIZE), spec.materialCode(), null, "ACTIVE",
                "effective_date", "DESC")
                .getRecords();
        if (actives.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "物料 " + spec.materialCode() + " 没有 ACTIVE 工艺路线，无法预估交期");
        }
        return actives.get(0);
    }

    private Map<UUID, List<OperationDependency>> dependencies(
            List<RoutingEdge> edges, Map<UUID, Integer> seqByNodeId, String orderNo) {
        Map<UUID, List<OperationDependency>> result = new LinkedHashMap<>();
        for (RoutingEdge edge : edges) {
            Integer fromSeq = seqByNodeId.get(edge.getFromNodeId());
            Integer toSeq = seqByNodeId.get(edge.getToNodeId());
            if (fromSeq == null || toSeq == null) {
                continue;
            }
            String dependType = edge.getDependType() == null
                    ? "FS" : edge.getDependType().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_DEPEND_TYPES.contains(dependType)) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单 " + orderNo + " 的工序依赖类型 " + dependType
                                + " 不在 V1 支持范围（FS/SS/FF），已拒绝求解");
            }
            int lagMinutes = edge.getLagMinutes() == null ? 0 : edge.getLagMinutes();
            if (lagMinutes < 0) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单 " + orderNo + " 的工序依赖 lagMinutes 不能为负数");
            }
            result.computeIfAbsent(edge.getToNodeId(), key -> new ArrayList<>())
                    .add(new OperationDependency(fromSeq, dependType, lagMinutes));
        }
        return result;
    }

    private SchedulingOperation toOperation(RoutingNode node, Map<String, OperationTemplate> templateCache,
                                            List<OperationDependency> dependencies,
                                            Map<UUID, SchedulingResource> resourceById) {
        OperationTemplate template = null;
        if (node.getOperationTemplateId() != null) {
            template = templateCache.computeIfAbsent(node.getOperationTemplateId().toString(),
                    key -> operationTemplateMapper.selectById(node.getOperationTemplateId()));
        }
        Integer standardSeconds = node.getStandardSeconds();
        if (standardSeconds == null && template != null) {
            standardSeconds = template.getStandardSeconds();
        }
        if (standardSeconds == null) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "工序 " + node.getNodeKey() + " 缺少标准工时（routing_node/operation_template 均为空）");
        }
        Integer setupSeconds = node.getSetupSeconds();
        if (setupSeconds == null && template != null) {
            setupSeconds = template.getSetupSeconds();
        }
        UUID primaryResourceId = primaryResourceId(node, template, resourceById);
        UUID primaryResourceGroupId = template == null ? null : template.getPrimaryResourceGroupId();
        List<UUID> secondaryResourceIds = secondaryResourceIds(node, template);
        String operationCode = node.getOperationCode() != null
                ? node.getOperationCode() : template == null ? null : template.getCode();
        String operationName = node.getOperationName() != null
                ? node.getOperationName() : template == null ? null : template.getName();
        return new SchedulingOperation(
                node.getSeqNo(),
                operationCode,
                operationName,
                node.getOperationTemplateId(),
                BigDecimal.valueOf(standardSeconds),
                BigDecimal.valueOf(setupSeconds == null ? 0 : setupSeconds),
                primaryResourceId,
                primaryResourceGroupId,
                secondaryResourceIds,
                Boolean.TRUE.equals(node.getIsOptional()),
                dependencies == null ? List.of() : dependencies);
    }

    /**
     * 浮动工序候选资源时长一致性前置校验（C15 §20.3.1.4）。
     *
     * <p>求解模型在建模型时为每个任务只算一次 {@code runSeconds} 并写死，换资源不重算；
     * 因此浮动工序的候选集合内各资源算出的 {@code runSeconds} 必须一致。候选集合：
     * 任务带 {@code RESOURCE} 锁且集合非空时取锁集合，否则取场景内全部启用资源。
     * 不一致时装配阶段直接拒绝（{@code 42200}），不静默取第一个资源代算。</p>
     *
     * <p>固定主资源工序只有一个候选，无需校验。</p>
     *
     * @param orders 参与求解的订单
     * @param resourceById 场景内资源
     * @param assignments 既有分配（携带任务级 {@code RESOURCE} 锁）
     */
    private void validateFloatOperationDurations(
            List<SchedulingOrder> orders,
            Map<UUID, SchedulingResource> resourceById,
            List<TaskAssignment> assignments) {
        Map<String, TaskAssignment> assignmentByKey = new HashMap<>();
        for (TaskAssignment assignment : assignments) {
            assignmentByKey.put(orderTaskKey(assignment.orderId(), assignment.operationIndex()), assignment);
        }
        List<String> mismatches = new ArrayList<>();
        for (SchedulingOrder order : orders) {
            for (SchedulingOperation operation : order.operations()) {
                if (operation.primaryResourceId() != null) {
                    continue;
                }
                TaskAssignment existing = assignmentByKey.get(
                        orderTaskKey(order.orderId(), operation.operationIndex()));
                List<SchedulingResource> candidates = candidateResources(resourceById, existing);
                if (candidates.size() <= 1) {
                    continue;
                }
                Map<Long, List<String>> codesByRunSeconds = new TreeMap<>();
                for (SchedulingResource resource : candidates) {
                    codesByRunSeconds.computeIfAbsent(
                            OperationRunDuration.seconds(resource, operation, order.qty()),
                            key -> new ArrayList<>()).add(resource.code());
                }
                if (codesByRunSeconds.size() > 1) {
                    String operationLabel = operation.operationCode() == null
                            ? "OP" + operation.operationIndex() : operation.operationCode();
                    mismatches.add("订单 " + order.orderNo() + " / 工序 " + operationLabel
                            + "（序号 " + operation.operationIndex() + "）候选资源: "
                            + codesByRunSeconds.entrySet().stream()
                            .map(entry -> String.join("/", entry.getValue())
                                    + "(runSeconds=" + entry.getKey() + ")")
                            .collect(Collectors.joining(", ")));
                }
            }
        }
        if (!mismatches.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "浮动工序的候选资源算出的 runSeconds 不一致，已拒绝求解（C15 §20.3.1.4）: "
                            + String.join("; ", mismatches));
        }
    }

    /**
     * 浮动工序的候选资源集合：有 {@code RESOURCE} 锁取锁集合，否则取全场景。
     *
     * @param resourceById 场景内资源（按 code 升序）
     * @param existing 既有分配，可空
     * @return 候选资源
     */
    private List<SchedulingResource> candidateResources(
            Map<UUID, SchedulingResource> resourceById, TaskAssignment existing) {
        if (existing != null && "RESOURCE".equals(existing.lockType())
                && existing.lockedResourceIds() != null && !existing.lockedResourceIds().isEmpty()) {
            List<SchedulingResource> locked = existing.lockedResourceIds().stream()
                    .map(resourceById::get)
                    .filter(resource -> resource != null)
                    .toList();
            if (!locked.isEmpty()) {
                return locked;
            }
        }
        return List.copyOf(resourceById.values());
    }

    /**
     * 任务键：订单标识 + 工序序号。
     *
     * @param orderId 订单标识
     * @param operationIndex 工序序号
     * @return 任务键
     */
    private static String orderTaskKey(UUID orderId, int operationIndex) {
        return orderId + "#" + operationIndex;
    }

    private UUID primaryResourceId(RoutingNode node, OperationTemplate template,
                                   Map<UUID, SchedulingResource> resourceById) {
        Map<String, Object> overrides = node.getOverrides() == null ? Map.of() : node.getOverrides();
        if (overrides.containsKey("primaryResourceId")) {
            Object value = overrides.get("primaryResourceId");
            if (value == null) {
                // 显式 null：工序主资源浮动，由求解器在资源锁/可用资源范围内选择
                return null;
            }
            UUID override = uuidValue(value);
            if (override != null) {
                requireResource(override, node, resourceById);
                return override;
            }
        }
        UUID fromTemplate = template == null ? null : template.getPrimaryResourceId();
        if (fromTemplate != null) {
            requireResource(fromTemplate, node, resourceById);
            return fromTemplate;
        }
        UUID groupId = template == null ? null : template.getPrimaryResourceGroupId();
        if (groupId != null) {
            return resourceById.values().stream()
                    .filter(resource -> groupId.equals(resource.groupId()))
                    .map(SchedulingResource::resourceId)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "工序 " + node.getNodeKey() + " 的主资源组下没有资源"));
        }
        if (resourceById.size() == 1) {
            return resourceById.values().iterator().next().resourceId();
        }
        throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                "工序 " + node.getNodeKey() + " 缺少主资源，无法排产");
    }

    private List<UUID> secondaryResourceIds(RoutingNode node, OperationTemplate template) {
        Map<String, Object> overrides = node.getOverrides() == null ? Map.of() : node.getOverrides();
        if (overrides.containsKey("secondaryResourceIds")) {
            Object value = overrides.get("secondaryResourceIds");
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>(list.size());
            for (Object item : list) {
                UUID id = uuidValue(item);
                if (id != null) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        }
        return template == null || template.getSecondaryResourceIds() == null
                ? List.of() : List.copyOf(template.getSecondaryResourceIds());
    }

    private void requireResource(UUID resourceId, RoutingNode node,
                                 Map<UUID, SchedulingResource> resourceById) {
        if (!resourceById.containsKey(resourceId)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "工序 " + node.getNodeKey() + " 引用的资源不存在或已删除: " + resourceId);
        }
    }

    private List<SchedulingResource> buildResources(OffsetDateTime from, OffsetDateTime to) {
        List<Resource> resources = new ArrayList<>(resourceMapper.selectList(null));
        resources.sort(Comparator.comparing(Resource::getCode));
        Calendar factoryCalendar = defaultFactoryCalendar();
        Map<UUID, List<AvailabilityWindow>> windowCache = new HashMap<>();
        List<SchedulingResource> result = new ArrayList<>(resources.size());
        for (Resource resource : resources) {
            UUID calendarId = resource.getCalendarId();
            if (calendarId == null && resource.getGroupId() != null) {
                ResourceGroup group = resourceGroupMapper.selectById(resource.getGroupId());
                if (group != null) {
                    calendarId = group.getCalendarId();
                }
            }
            Calendar calendar = calendarId == null ? factoryCalendar : calendarMapper.findById(calendarId);
            UUID effectiveCalendarId = calendar == null || !(calendar.getId() instanceof UUID id) ? null : id;
            List<AvailabilityWindow> windows = windowCache.computeIfAbsent(
                    effectiveCalendarId, key -> expand(calendar, from, to));
            result.add(new SchedulingResource(
                    requireUuid(resource.getId()),
                    resource.getCode(),
                    resource.getType() == null ? null : resource.getType().name(),
                    resource.getGroupId(),
                    resource.getCapacityMode() == null ? null : resource.getCapacityMode().name(),
                    resource.getCapacityValue(),
                    resource.getBatchCapacity(),
                    resource.getEfficiency() == null ? BigDecimal.ONE : resource.getEfficiency(),
                    calendarId,
                    "ACTIVE",
                    windows));
        }
        return result;
    }

    private Calendar defaultFactoryCalendar() {
        List<Calendar> calendars = calendarMapper.selectPage(
                new Page<>(1, CALENDAR_PAGE_SIZE), null, "FACTORY", null, null);
        return calendars.stream()
                .filter(calendar -> calendar.getEffectiveFrom() == null
                        || !calendar.getEffectiveFrom().isAfter(LocalDate.now(businessZone)))
                .max(Comparator.comparing(calendar -> calendar.getCalendarVersion() == null
                        ? 0 : calendar.getCalendarVersion()))
                .orElse(null);
    }

    /**
     * 把日历班次/例外日期展开为 UTC 半开可用窗口，按 start 升序且互不重叠。
     *
     * @param calendar 日历，可空
     * @param from 时间窗开始
     * @param to 时间窗结束
     * @return 可用时段；展开为空时返回空列表，由求解器标记 calendarFallback
     */
    List<AvailabilityWindow> expand(Calendar calendar, OffsetDateTime from, OffsetDateTime to) {
        if (calendar == null || calendar.getShifts() == null || calendar.getShifts().isEmpty()) {
            return List.of();
        }
        if (calendar.getEffectiveFrom() != null
                && calendar.getEffectiveFrom().isAfter(to.atZoneSameInstant(businessZone).toLocalDate())) {
            return List.of();
        }
        LocalDate cursor = from.atZoneSameInstant(businessZone).toLocalDate();
        LocalDate last = to.atZoneSameInstant(businessZone).toLocalDate();
        if (calendar.getEffectiveFrom() != null && calendar.getEffectiveFrom().isAfter(cursor)) {
            cursor = calendar.getEffectiveFrom();
        }
        if (calendar.getEffectiveTo() != null && calendar.getEffectiveTo().isBefore(last)) {
            last = calendar.getEffectiveTo();
        }
        Map<LocalDate, String> holidayKind = new HashMap<>();
        if (calendar.getHolidays() != null) {
            for (Map<String, Object> holiday : calendar.getHolidays()) {
                Object date = holiday.get("date");
                if (date == null) {
                    continue;
                }
                holidayKind.put(LocalDate.parse(String.valueOf(date)),
                        holiday.get("kind") == null ? "LEGAL" : String.valueOf(holiday.get("kind")));
            }
        }
        List<AvailabilityWindow> windows = new ArrayList<>();
        for (LocalDate date = cursor; !date.isAfter(last); date = date.plusDays(1)) {
            String kind = holidayKind.get(date);
            if ("LEGAL".equalsIgnoreCase(kind) || "COMPANY".equalsIgnoreCase(kind)) {
                continue;
            }
            for (Map<String, Object> shift : calendar.getShifts()) {
                if (!applies(shift, date)) {
                    continue;
                }
                LocalTime startTime = parseTime(shift.get("startTime"));
                LocalDateTime start = date.atTime(startTime);
                LocalDateTime end = shiftEnd(date, startTime, shift.get("endTime"));
                addWindow(windows, start.atZone(businessZone).toOffsetDateTime(),
                        end.atZone(businessZone).toOffsetDateTime(), from, to);
            }
        }
        windows.sort(Comparator.comparing(AvailabilityWindow::start));
        List<AvailabilityWindow> merged = new ArrayList<>(windows.size());
        for (AvailabilityWindow window : windows) {
            if (!merged.isEmpty() && !window.start().isAfter(merged.get(merged.size() - 1).end())) {
                AvailabilityWindow previous = merged.remove(merged.size() - 1);
                OffsetDateTime end = window.end().isAfter(previous.end()) ? window.end() : previous.end();
                if (end.isAfter(previous.start())) {
                    merged.add(new AvailabilityWindow(previous.start(), end));
                }
            } else {
                merged.add(window);
            }
        }
        return List.copyOf(merged);
    }

    private static boolean applies(Map<String, Object> shift, LocalDate date) {
        Object days = shift.get("daysOfWeek");
        if (days instanceof List<?> list && !list.isEmpty()) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            for (Object item : list) {
                if (item instanceof Number number && number.intValue() == dayOfWeek) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static void addWindow(List<AvailabilityWindow> windows, OffsetDateTime start,
                                  OffsetDateTime end, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime clippedStart = start.isBefore(from) ? from : start;
        OffsetDateTime clippedEnd = end.isAfter(to) ? to : end;
        if (clippedEnd.isAfter(clippedStart)) {
            windows.add(new AvailabilityWindow(clippedStart, clippedEnd));
        }
    }

    private static LocalTime parseTime(Object value) {
        String text = String.valueOf(value == null ? "00:00" : value).trim();
        String[] parts = text.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return LocalTime.of(hour, minute);
    }

    private static LocalDateTime shiftEnd(LocalDate date, LocalTime startTime, Object value) {
        String text = String.valueOf(value == null ? "24:00" : value).trim();
        if ("24:00".equals(text)) {
            return date.plusDays(1).atStartOfDay();
        }
        LocalTime endTime = parseTime(text);
        if (endTime.equals(LocalTime.MIDNIGHT) || !endTime.isAfter(startTime)) {
            return date.plusDays(1).atTime(endTime);
        }
        return date.atTime(endTime);
    }

    private static List<TaskAssignment> buildFrozenAssignments(
            List<FrozenTaskRow> rows, boolean forcePinned) {
        List<TaskAssignment> assignments = new ArrayList<>(rows.size());
        for (FrozenTaskRow row : rows) {
            boolean frozenLevel = forcePinned
                    || "FROZEN".equals(row.status())
                    || "FROZEN".equals(row.lockType());
            assignments.add(new TaskAssignment(
                    row.taskId(),
                    row.orderId(),
                    row.orderNo(),
                    row.operationIndex(),
                    null,
                    row.operationTemplateId(),
                    row.resourceId(),
                    row.resourceCode(),
                    row.secondaryResourceIds(),
                    row.setupStartTime(),
                    row.setupEndTime() == null ? row.startTime() : row.setupEndTime(),
                    row.startTime(),
                    row.endTime(),
                    row.qty(),
                    null,
                    row.status(),
                    frozenLevel,
                    row.version(),
                    row.lockType(),
                    row.lockedFrom(),
                    row.lockedTo(),
                    row.lockedResourceIds()));
        }
        return List.copyOf(assignments);
    }

    private static UUID uuidValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static UUID requireUuid(java.io.Serializable value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    /**
     * 本次求解采用的 ACTIVE 约束 DSL 快照。
     *
     * @param dslId DSL 行主键
     * @param code DSL 标识
     * @param versionNo 版本号
     * @param checksum YAML 校验和
     * @param constraints 映射后的引擎约束
     * @param weights 8 条规则权重（合计 100）
     */
    public record ActiveConstraintDsl(
            UUID dslId,
            String code,
            int versionNo,
            String checksum,
            List<ConstraintSpec> constraints,
            Map<String, Integer> weights) {
    }

    /**
     * 装配结果。
     *
     * @param tenantId 租户标识
     * @param sandboxId 场景标识
     * @param problem 求解问题
     * @param orderIds 参与订单主键
     * @param activeConstraintDsl 本次采用的 ACTIVE 约束 DSL；无则为 null
     */
    public record AssembledProblem(UUID tenantId, UUID sandboxId, ScheduleProblem problem,
                                   List<UUID> orderIds, ActiveConstraintDsl activeConstraintDsl) {

        /**
         * 兼容纯硬约束装配的单测构造。
         *
         * @param tenantId 租户标识
         * @param sandboxId 场景标识
         * @param problem 求解问题
         * @param orderIds 参与订单主键
         */
        public AssembledProblem(UUID tenantId, UUID sandboxId, ScheduleProblem problem,
                                List<UUID> orderIds) {
            this(tenantId, sandboxId, problem, orderIds, null);
        }

        /**
         * 本次场景应参与排产的任务数（= 装配问题内全部订单的工序数之和）。
         *
         * <p>这是完整性校验的唯一口径：求解结果 {@code assignments.size()} 必须与它相等，
         * 否则说明构造启发式未排满，不得判定 {@code SOLVED}、不得落库。</p>
         *
         * @return 期望任务数
         */
        public int expectedTaskCount() {
            if (problem == null) {
                return 0;
            }
            int count = 0;
            for (SchedulingOrder order : problem.orders()) {
                count += order.operations().size();
            }
            return count;
        }

        /**
         * 本次实际请求重排的订单任务数（不含仅作为约束参与装配的 pinned 订单）。
         *
         * <p>增量重排时 {@code problem.orders()} 同时包含重排订单与 pinned 订单，
         * 而落库只覆盖 {@code orderIds} 对应的订单；因此写入口径必须与 orderIds 对齐。</p>
         *
         * @return 可写入任务数
         */
        public int expectedWriteTaskCount() {
            if (problem == null) {
                return 0;
            }
            Set<UUID> requestedOrderIds = new HashSet<>(orderIds);
            int count = 0;
            for (SchedulingOrder order : problem.orders()) {
                if (requestedOrderIds.contains(order.orderId())) {
                    count += order.operations().size();
                }
            }
            return count;
        }
    }

    /**
     * 订单在求解窗口起点之前已完成的 FROZEN 工序。
     *
     * @param operationIndices 已完工工序序号（这些工序从题面移除）
     * @param maxEndTime 已完成工序的最大结束时间，作为剩余工序的硬下界
     */
    private record CompletedFrozenOps(Set<Integer> operationIndices, OffsetDateTime maxEndTime) {
    }
}
