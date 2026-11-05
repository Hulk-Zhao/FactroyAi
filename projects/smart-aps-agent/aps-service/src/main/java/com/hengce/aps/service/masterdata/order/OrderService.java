package com.hengce.aps.service.masterdata.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.model.FreezeStatus;
import com.hengce.aps.domain.model.OrderStatus;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import com.hengce.aps.infrastructure.persistence.order.Order;
import com.hengce.aps.infrastructure.persistence.order.OrderMapper;
import com.hengce.aps.infrastructure.persistence.order.OrderTaskFreezeRepository;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.service.audit.AuditValues;
import com.hengce.aps.service.masterdata.bom.BomService;
import com.hengce.aps.service.masterdata.bom.BomStatus;
import com.hengce.aps.service.mrp.MrpSuggestionCache;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 订单管理服务，端点与语义对齐 contract-freeze C13，筛选语义对齐 C10，确认校验对齐 C8。
 *
 * <p>订单状态与冻结状态是两条独立状态轴：{@code status} 只由创建/确认推进，
 * {@code freeze_status} 只由 freeze 端点修改，两者互不影响。</p>
 */
@Service
public class OrderService {

    /**
     * 订单号模糊匹配 SQL，{0} 为转义后的关键字，参数化绑定避免注入。
     */
    private static final String ORDER_NO_LIKE_SQL = "LOWER(order_no) LIKE LOWER(CONCAT('%', {0}, '%'))";

    /**
     * 产品物料编码模糊匹配 SQL，用于筛选时先定位 product_id。
     */
    private static final String MATERIAL_CODE_LIKE_SQL = "LOWER(code) LIKE LOWER(CONCAT('%', {0}, '%'))";

    private static final String DUPLICATE_ORDER_NO_MESSAGE = "订单号已存在: ";

    private static final String VERSION_CONFLICT_MESSAGE = "订单已被其他请求修改，请刷新后重试";

    /**
     * 订单冻结变更的审计动作编码。
     */
    private static final String AUDIT_ORDER_FROZEN = "ORDER_FROZEN";

    /**
     * 订单优先级变更的审计动作编码。
     */
    private static final String AUDIT_ORDER_PRIORITY = "ORDER_PRIORITY_CHANGED";

    /**
     * 订单释放的审计动作编码。
     */
    private static final String AUDIT_ORDER_RELEASED = "ORDER_RELEASED";

    /**
     * 订单开工的审计动作编码。
     */
    private static final String AUDIT_ORDER_STARTED = "ORDER_STARTED";

    /**
     * 审计资源类型。
     */
    private static final String AUDIT_RESOURCE_ORDER = "ORDER";

    /**
     * 工艺路线 ACTIVE 状态字面量，与 BD-07 的 routing.status 取值一致。
     */
    private static final String ROUTING_ACTIVE = "ACTIVE";

    private final OrderMapper orderMapper;

    private final MaterialMapper materialMapper;

    private final RoutingMapper routingMapper;

    private final BomService bomService;

    private final AuditLogRepository auditLogRepository;

    private final OrderTaskFreezeRepository orderTaskFreezeRepository;

    private final ResourceMapper resourceMapper;

    private final MrpSuggestionCache suggestionCache;

    public OrderService(OrderMapper orderMapper, MaterialMapper materialMapper,
                        RoutingMapper routingMapper, BomService bomService,
                        AuditLogRepository auditLogRepository,
                        OrderTaskFreezeRepository orderTaskFreezeRepository,
                        ResourceMapper resourceMapper, MrpSuggestionCache suggestionCache) {
        this.orderMapper = orderMapper;
        this.materialMapper = materialMapper;
        this.routingMapper = routingMapper;
        this.bomService = bomService;
        this.auditLogRepository = auditLogRepository;
        this.orderTaskFreezeRepository = orderTaskFreezeRepository;
        this.resourceMapper = resourceMapper;
        this.suggestionCache = suggestionCache;
    }

    /**
     * 分页查询订单。
     *
     * <p>C10 语义：{@code orderNo} 与 {@code materialCode} 之间 OR（模糊、忽略大小写），
     * 与 {@code status}/{@code customerId}/交期区间之间 AND；空白关键字视为未传。</p>
     *
     * @param query 分页参数，可为 null；内部会 clamp
     * @param status 订单状态（精确）
     * @param orderNo 订单号关键字（模糊）
     * @param materialCode 产品物料编码关键字（模糊）
     * @param customerId 客户主键（精确）
     * @param dueDateFrom 交期下界（含）
     * @param dueDateTo 交期上界（含）
     * @return 分页结果
     */
    public PageResult<OrderView> list(PageQuery query, OrderStatus status, String orderNo,
                                      String materialCode, UUID customerId,
                                      LocalDate dueDateFrom, LocalDate dueDateTo) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        String orderNoKeyword = trimToNull(orderNo);
        String materialKeyword = trimToNull(materialCode);
        List<UUID> materialIds = materialKeyword == null ? List.of() : findMaterialIds(materialKeyword);
        if (materialKeyword != null && materialIds.isEmpty() && orderNoKeyword == null) {
            return new PageResult<>(List.of(), normalized.getPage(), normalized.getSize(), 0, 0);
        }
        LambdaQueryWrapper<Order> wrapper = buildListWrapper(
                orderNoKeyword, materialIds, status, customerId, dueDateFrom, dueDateTo);
        applySort(wrapper, normalized.getSort());
        Page<Order> page = new Page<>(normalized.getPage(), normalized.getSize());
        Page<Order> result = orderMapper.selectPage(page, wrapper);
        List<OrderView> items = toViews(result.getRecords());
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), result.getTotal(), 0);
    }

    /**
     * 按订单号查询订单详情。
     *
     * @param orderNo 订单业务编码
     * @return 订单视图
     */
    public OrderView get(String orderNo) {
        return toView(findByOrderNoOrThrow(orderNo), null);
    }

    /**
     * 新建订单，默认状态 DRAFT、冻结状态 UNFROZEN、来源 MANUAL、优先级 NORMAL。
     *
     * @param command 创建命令
     * @return 创建后的订单视图
     */
    public OrderView create(OrderCreateCommand command) {
        String orderNo = trimToNull(command.orderNo());
        if (orderNo == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单号不能为空");
        }
        Material product = requireProduct(command.materialCode());
        validateCustomer(command.customerId());
        validateRouting(command.routingId());
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNo(orderNo);
        order.setSource((command.source() == null ? OrderSource.MANUAL : command.source()).name());
        order.setProductId((UUID) product.getId());
        order.setQty(command.qty());
        order.setDueDate(command.dueDate());
        order.setPriority((command.priority() == null ? OrderPriority.NORMAL : command.priority()).name());
        order.setCustomerId(command.customerId());
        order.setRoutingId(command.routingId());
        order.setStatus(OrderStatus.DRAFT.name());
        order.setFreezeStatus(FreezeStatus.UNFROZEN.name());
        order.setRemark(command.remark());
        order.setExtAttrs(copyAttributes(command.extAttrs()));
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, DUPLICATE_ORDER_NO_MESSAGE + orderNo);
        }
        suggestionCache.invalidate(TenantContext.getTenantId());
        return toView(order, product.getCode());
    }

    /**
     * 更新 DRAFT 订单；订单号不可修改，更新必须携带当前 version。
     *
     * @param orderNo 路径中的订单号
     * @param command 更新命令
     * @return 更新后的订单视图
     */
    public OrderView update(String orderNo, OrderUpdateCommand command) {
        Order existing = findByOrderNoOrThrow(orderNo);
        if (!OrderStatus.DRAFT.name().equals(existing.getStatus())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "只有 DRAFT 状态的订单可修改，当前状态为 " + existing.getStatus());
        }
        if (command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Material newProduct = null;
        if (trimToNull(command.materialCode()) != null) {
            newProduct = requireProduct(command.materialCode());
            existing.setProductId((UUID) newProduct.getId());
        }
        if (command.qty() != null) {
            existing.setQty(command.qty());
        }
        if (command.dueDate() != null) {
            existing.setDueDate(command.dueDate());
        }
        if (command.priority() != null) {
            existing.setPriority(command.priority().name());
        }
        if (command.customerId() != null) {
            validateCustomer(command.customerId());
            existing.setCustomerId(command.customerId());
        }
        if (command.routingId() != null) {
            validateRouting(command.routingId());
            existing.setRoutingId(command.routingId());
        }
        if (command.remark() != null) {
            existing.setRemark(command.remark());
        }
        if (command.extAttrs() != null) {
            existing.setExtAttrs(copyAttributes(command.extAttrs()));
        }
        int rows = orderMapper.updateById(existing);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Order latest = findByOrderNo(orderNo);
        suggestionCache.invalidate(TenantContext.getTenantId());
        return toView(latest == null ? existing : latest, newProduct == null ? null : newProduct.getCode());
    }

    /**
     * 确认订单：DRAFT → CONFIRMED，重复确认幂等返回当前视图。
     *
     * <p>C8 校验：订单数量与交期、产品物料存在、唯一 ACTIVE BOM、唯一 ACTIVE 工艺路线；
     * 任一失败返回 42200 且消息可直接展示给用户。BOM 校验复用 BD-03 的 BomService 查询能力，
     * 工艺路线校验复用 BD-07 的 RoutingMapper.countActiveByProduct。</p>
     *
     * @param orderNo 订单业务编码
     * @return 确认后的订单视图
     */
    public OrderView confirm(String orderNo) {
        Order existing = findByOrderNoOrThrow(orderNo);
        if (OrderStatus.CONFIRMED.name().equals(existing.getStatus())) {
            return toView(existing, null);
        }
        if (!OrderStatus.DRAFT.name().equals(existing.getStatus())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "只有 DRAFT 状态的订单可确认，当前状态为 " + existing.getStatus());
        }
        if (existing.getQty() == null || existing.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单数量必须大于 0 才能确认");
        }
        if (existing.getDueDate() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单交期不能为空才能确认");
        }
        Material product = materialMapper.selectById(existing.getProductId());
        if (product == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "产品物料不存在或已删除，无法确认订单: " + existing.getProductId());
        }
        verifyUniqueActiveBom(product);
        verifyUniqueActiveRouting(existing, product);
        Order update = new Order();
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        update.setStatus(OrderStatus.CONFIRMED.name());
        int rows = orderMapper.updateById(update);
        if (rows == 0) {
            Order latest = findByOrderNo(orderNo);
            if (latest != null && OrderStatus.CONFIRMED.name().equals(latest.getStatus())) {
                return toView(latest, null);
            }
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Order latest = findByOrderNo(orderNo);
        suggestionCache.invalidate(TenantContext.getTenantId());
        return toView(latest == null ? existing : latest, null);
    }

    /**
     * 修改冻结状态（C23 四态）；仅改 {@code freeze_status} 与 {@code ext_attrs.freezeLock}，
     * 不改 {@code status}，重复设置同一状态与锁定信息时幂等。
     *
     * <p>锁定信息规则：</p>
     * <ul>
     *   <li>{@code UNFROZEN}/{@code FROZEN}：清除 {@code ext_attrs.freezeLock}；</li>
     *   <li>{@code LOCKED_TIME_WINDOW}：必须提供 {@code lockFrom}/{@code lockTo} 且 {@code lockTo} 晚于 {@code lockFrom}；</li>
     *   <li>{@code LOCKED_RESOURCE}：必须提供至少一个非空 {@code lockedResourceCodes}。</li>
     * </ul>
     *
     * @param orderNo 订单业务编码
     * @param command 冻结命令
     * @return 更新后的订单视图
     */
    public OrderView freeze(String orderNo, OrderFreezeCommand command) {
        Order existing = findByOrderNoOrThrow(orderNo);
        FreezeStatus target = command.freezeStatus();
        if (target == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "freezeStatus 不能为空");
        }
        if (command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        FreezeLock targetLock = normalizeLock(target, command.lockFrom(), command.lockTo(),
                command.lockedResourceCodes());
        FreezeStatus current = FreezeStatus.valueOf(existing.getFreezeStatus());
        FreezeLock currentLock = OrderView.parseFreezeLock(existing.getExtAttrs());
        if (current == target && sameLock(currentLock, targetLock)) {
            return toView(existing, null);
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Map<String, Object> attributes = copyAttributes(existing.getExtAttrs());
        if (targetLock == null) {
            attributes.remove(OrderView.FREEZE_LOCK_ATTRIBUTE);
        } else {
            attributes.put(OrderView.FREEZE_LOCK_ATTRIBUTE, toAttribute(targetLock));
        }
        Order update = new Order();
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        update.setFreezeStatus(target.name());
        update.setExtAttrs(attributes);
        int rows = orderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        auditLogRepository.record(TenantContext.getTenantId(), AUDIT_ORDER_FROZEN,
                AUDIT_RESOURCE_ORDER, orderNo,
                AuditValues.redact(freezeSnapshot(current, currentLock)),
                AuditValues.redact(freezeSnapshot(target, targetLock)));
        Order latest = findByOrderNo(orderNo);
        return toView(latest == null ? existing : latest, null);
    }

    /**
     * 修改订单优先级（C23）；重复设置同一优先级时幂等，变更写入审计。
     *
     * @param orderNo 订单业务编码
     * @param priority 目标优先级
     * @param version 当前乐观锁版本
     * @return 更新后的订单视图
     */
    public OrderView changePriority(String orderNo, OrderPriority priority, Integer version) {
        Order existing = findByOrderNoOrThrow(orderNo);
        if (priority == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "priority 不能为空");
        }
        if (version == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (priority.name().equals(existing.getPriority())) {
            return toView(existing, null);
        }
        if (!Objects.equals(existing.getVersion(), version)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Order update = new Order();
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        update.setPriority(priority.name());
        // Order.extAttrs 默认初始化为空 Map，必须显式回填，避免局部更新清空冻结锁定信息
        update.setExtAttrs(copyAttributes(existing.getExtAttrs()));
        int rows = orderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("priority", existing.getPriority());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priority", priority.name());
        auditLogRepository.record(TenantContext.getTenantId(), AUDIT_ORDER_PRIORITY,
                AUDIT_RESOURCE_ORDER, orderNo, AuditValues.redact(before), AuditValues.redact(after));
        Order latest = findByOrderNo(orderNo);
        return toView(latest == null ? existing : latest, null);
    }

    /**
     * 释放订单（C23）：CONFIRMED/SCHEDULED → RELEASED，并自动冻结订单下仍为 PLANNED 的任务。
     *
     * <p>自动冻结规则同时适用于 {@code RELEASED} 与 {@code IN_PROGRESS}：任务置为 {@code FROZEN} 后
     * 重排不会重新安排其时间/资源。已释放重复调用幂等，不重复写审计。</p>
     *
     * @param orderNo 订单业务编码
     * @param reason 释放原因，可为空；写入审计
     * @param version 当前乐观锁版本，可为空表示不做版本校验
     * @return 更新后的订单视图
     */
    public OrderView release(String orderNo, String reason, Integer version) {
        Order existing = findByOrderNoOrThrow(orderNo);
        String status = existing.getStatus();
        if (OrderStatus.RELEASED.name().equals(status)) {
            return toView(existing, null);
        }
        if (!OrderStatus.CONFIRMED.name().equals(status) && !OrderStatus.SCHEDULED.name().equals(status)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "只有 CONFIRMED/SCHEDULED 状态的订单可释放，当前状态为 " + status);
        }
        if (version != null && !Objects.equals(existing.getVersion(), version)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Map<String, Object> attributes = copyAttributes(existing.getExtAttrs());
        // 释放即整单冻结，锁窗/锁资源信息随之失效，统一清除
        attributes.remove(OrderView.FREEZE_LOCK_ATTRIBUTE);
        Order update = new Order();
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        update.setStatus(OrderStatus.RELEASED.name());
        update.setFreezeStatus(FreezeStatus.FROZEN.name());
        update.setExtAttrs(attributes);
        int rows = orderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        int frozenTasks = orderTaskFreezeRepository.freezePlannedTasks(
                TenantContext.getTenantId(), (UUID) existing.getId());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", status);
        before.put("freezeStatus", existing.getFreezeStatus());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.RELEASED.name());
        after.put("freezeStatus", FreezeStatus.FROZEN.name());
        after.put("autoFrozenTaskCount", frozenTasks);
        after.put("reason", reason);
        auditLogRepository.record(TenantContext.getTenantId(), AUDIT_ORDER_RELEASED,
                AUDIT_RESOURCE_ORDER, orderNo, AuditValues.redact(before), AuditValues.redact(after));
        Order latest = findByOrderNo(orderNo);
        return toView(latest == null ? existing : latest, null);
    }

    /**
     * 订单开工（C23）：{@code RELEASED → IN_PROGRESS}，并自动冻结仍为 {@code PLANNED} 的任务。
     *
     * <p>重复开工幂等，不重复写审计；{@code version} 为契约必填，不匹配返回 {@code 40901}。</p>
     *
     * @param orderNo 订单业务编码
     * @param version 当前乐观锁版本
     * @return 更新后的订单视图
     */
    public OrderView start(String orderNo, Integer version) {
        Order existing = findByOrderNoOrThrow(orderNo);
        String status = existing.getStatus();
        if (OrderStatus.IN_PROGRESS.name().equals(status)) {
            return toView(existing, null);
        }
        if (!OrderStatus.RELEASED.name().equals(status)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "只有 RELEASED 状态的订单可开工，当前状态为 " + status);
        }
        if (version == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开工请求缺少 version");
        }
        if (!Objects.equals(existing.getVersion(), version)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        Order update = new Order();
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        update.setStatus(OrderStatus.IN_PROGRESS.name());
        int rows = orderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, VERSION_CONFLICT_MESSAGE);
        }
        int frozenTasks = orderTaskFreezeRepository.freezePlannedTasks(
                TenantContext.getTenantId(), (UUID) existing.getId());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", status);
        before.put("freezeStatus", existing.getFreezeStatus());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.IN_PROGRESS.name());
        after.put("freezeStatus", existing.getFreezeStatus());
        after.put("autoFrozenTaskCount", frozenTasks);
        auditLogRepository.record(TenantContext.getTenantId(), AUDIT_ORDER_STARTED,
                AUDIT_RESOURCE_ORDER, orderNo, AuditValues.redact(before), AuditValues.redact(after));
        Order latest = findByOrderNo(orderNo);
        return toView(latest == null ? existing : latest, null);
    }

    /**
     * 校验并规范化锁定信息；非锁定态返回 null。
     *
     * @param status 目标冻结状态
     * @param from 锁窗开始
     * @param to 锁窗结束
     * @param resourceCodes 锁定资源编码
     * @return 规范化后的锁定信息，非锁定态为 null
     */
    private FreezeLock normalizeLock(FreezeStatus status, OffsetDateTime from, OffsetDateTime to,
                                     List<String> resourceCodes) {
        if (status == FreezeStatus.LOCKED_TIME_WINDOW) {
            if (from == null || to == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "LOCKED_TIME_WINDOW 需要同时提供 lockFrom 与 lockTo");
            }
            if (!to.isAfter(from)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "lockTo 必须晚于 lockFrom");
            }
            return new FreezeLock(from, to, List.of());
        }
        if (status == FreezeStatus.LOCKED_RESOURCE) {
            FreezeLock lock = new FreezeLock(null, null, resourceCodes);
            List<String> codes = lock.normalizedResourceCodes();
            if (codes.isEmpty()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "LOCKED_RESOURCE 需要至少一个 lockedResourceCodes");
            }
            List<Resource> found = resourceMapper.selectList(Wrappers.<Resource>lambdaQuery()
                    .in(Resource::getCode, codes));
            List<String> missing = codes.stream()
                    .filter(code -> found.stream().noneMatch(resource -> code.equals(resource.getCode())))
                    .toList();
            if (!missing.isEmpty()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "锁定资源不存在: " + missing);
            }
            return new FreezeLock(null, null, codes);
        }
        return null;
    }

    /**
     * 锁定信息写入 {@code ext_attrs} 的结构；时间以 ISO-8601 字符串存储，避免 JSONB 序列化器依赖。
     *
     * @param lock 锁定信息
     * @return 可直接写入 ext_attrs 的 Map
     */
    private static Map<String, Object> toAttribute(FreezeLock lock) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (lock.from() != null) {
            map.put("from", lock.from().toInstant().toString());
        }
        if (lock.to() != null) {
            map.put("to", lock.to().toInstant().toString());
        }
        List<String> codes = lock.normalizedResourceCodes();
        if (!codes.isEmpty()) {
            map.put("resourceCodes", codes);
        }
        return map;
    }

    private static boolean sameLock(FreezeLock left, FreezeLock right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return sameInstant(left.from(), right.from())
                && sameInstant(left.to(), right.to())
                && left.normalizedResourceCodes().equals(right.normalizedResourceCodes());
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.toInstant().equals(right.toInstant());
    }

    private static Map<String, Object> freezeSnapshot(FreezeStatus status, FreezeLock lock) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("freezeStatus", status == null ? null : status.name());
        if (lock != null) {
            if (lock.from() != null) {
                snapshot.put("lockFrom", lock.from().toInstant().toString());
            }
            if (lock.to() != null) {
                snapshot.put("lockTo", lock.to().toInstant().toString());
            }
            if (!lock.normalizedResourceCodes().isEmpty()) {
                snapshot.put("lockedResourceCodes", lock.normalizedResourceCodes());
            }
        }
        return snapshot;
    }

    private void verifyUniqueActiveBom(Material product) {
        long activeCount = bomService.list(new PageQuery(), product.getCode(), BomStatus.ACTIVE).total();
        if (activeCount == 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "产品 " + product.getCode() + " 缺少唯一 ACTIVE 的 BOM，无法确认订单");
        }
        if (activeCount > 1) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "产品 " + product.getCode() + " 存在多个 ACTIVE 的 BOM，无法确认订单");
        }
    }

    private void verifyUniqueActiveRouting(Order order, Material product) {
        if (order.getRoutingId() != null) {
            Routing routing = routingMapper.selectById(order.getRoutingId());
            if (routing == null || !ROUTING_ACTIVE.equals(routing.getStatus())) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "订单指定的工艺路线不存在或不是 ACTIVE，无法确认订单");
            }
            return;
        }
        long activeCount = routingMapper.countActiveByProduct((UUID) product.getId(), null);
        if (activeCount == 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "产品 " + product.getCode() + " 缺少唯一 ACTIVE 的工艺路线，无法确认订单");
        }
        if (activeCount > 1) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "产品 " + product.getCode() + " 存在多个 ACTIVE 的工艺路线，无法确认订单");
        }
    }

    private Material requireProduct(String materialCode) {
        String code = trimToNull(materialCode);
        if (code == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "产品物料编码不能为空");
        }
        Material material = materialMapper.selectOne(
                Wrappers.<Material>lambdaQuery().eq(Material::getCode, code));
        if (material == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "产品物料不存在或已删除: " + code);
        }
        return material;
    }

    private void validateCustomer(UUID customerId) {
        if (customerId != null && orderMapper.countCustomer(customerId) == 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "客户不存在: " + customerId);
        }
    }

    private void validateRouting(UUID routingId) {
        if (routingId != null && routingMapper.selectById(routingId) == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "工艺路线不存在: " + routingId);
        }
    }

    private List<UUID> findMaterialIds(String materialCode) {
        return materialMapper.selectList(Wrappers.<Material>lambdaQuery()
                        .apply(MATERIAL_CODE_LIKE_SQL, escapeLike(materialCode)))
                .stream()
                .map(material -> (UUID) material.getId())
                .toList();
    }

    private Order findByOrderNoOrThrow(String orderNo) {
        Order order = findByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在: " + orderNo);
        }
        return order;
    }

    private Order findByOrderNo(String orderNo) {
        String normalized = trimToNull(orderNo);
        if (normalized == null) {
            return null;
        }
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, normalized));
    }

    LambdaQueryWrapper<Order> buildListWrapper(String orderNoKeyword, List<UUID> materialIds,
                                               OrderStatus status, UUID customerId,
                                               LocalDate dueDateFrom, LocalDate dueDateTo) {
        LambdaQueryWrapper<Order> wrapper = Wrappers.lambdaQuery();
        if (orderNoKeyword != null && materialIds != null && !materialIds.isEmpty()) {
            wrapper.and(nested -> nested
                    .apply(ORDER_NO_LIKE_SQL, escapeLike(orderNoKeyword))
                    .or()
                    .in(Order::getProductId, materialIds));
        } else if (orderNoKeyword != null) {
            wrapper.apply(ORDER_NO_LIKE_SQL, escapeLike(orderNoKeyword));
        } else if (materialIds != null && !materialIds.isEmpty()) {
            wrapper.in(Order::getProductId, materialIds);
        }
        if (status != null) {
            wrapper.eq(Order::getStatus, status.name());
        }
        if (customerId != null) {
            wrapper.eq(Order::getCustomerId, customerId);
        }
        if (dueDateFrom != null) {
            wrapper.ge(Order::getDueDate, dueDateFrom);
        }
        if (dueDateTo != null) {
            wrapper.le(Order::getDueDate, dueDateTo);
        }
        return wrapper;
    }

    private List<OrderView> toViews(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<UUID> productIds = orders.stream()
                .map(Order::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> codes = new LinkedHashMap<>();
        if (!productIds.isEmpty()) {
            for (Material material : materialMapper.selectBatchIds(productIds)) {
                codes.put((UUID) material.getId(), material.getCode());
            }
        }
        Map<UUID, OrderMapper.CustomerSummaryRow> customers = findCustomerSummaries(
                orders.stream()
                        .map(Order::getCustomerId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
        return orders.stream()
                .map(order -> OrderView.from(order, codes.get(order.getProductId()),
                        customerCode(customers, order.getCustomerId()),
                        customerName(customers, order.getCustomerId())))
                .toList();
    }

    private OrderView toView(Order order, String materialCode) {
        String code = materialCode;
        if (code == null && order.getProductId() != null) {
            Material material = materialMapper.selectById(order.getProductId());
            code = material == null ? null : material.getCode();
        }
        Map<UUID, OrderMapper.CustomerSummaryRow> customers = findCustomerSummaries(
                order.getCustomerId() == null ? List.of() : List.of(order.getCustomerId()));
        return OrderView.from(order, code,
                customerCode(customers, order.getCustomerId()),
                customerName(customers, order.getCustomerId()));
    }

    /**
     * 按客户主键集合一次性读取客户编码/名称（C50），避免列表逐条查询造成 N+1。
     *
     * @param customerIds 去重后的客户主键，可为空集合
     * @return 客户主键到摘要的映射；未命中/已软删除的客户不在映射中
     */
    private Map<UUID, OrderMapper.CustomerSummaryRow> findCustomerSummaries(List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, OrderMapper.CustomerSummaryRow> summaries = new LinkedHashMap<>();
        for (OrderMapper.CustomerSummaryRow row
                : orderMapper.selectCustomerSummaries(TenantContext.getTenantId(), customerIds)) {
            summaries.put(row.customerId(), row);
        }
        return summaries;
    }

    private static String customerCode(Map<UUID, OrderMapper.CustomerSummaryRow> customers, UUID customerId) {
        OrderMapper.CustomerSummaryRow customer = customerId == null ? null : customers.get(customerId);
        return customer == null ? null : customer.code();
    }

    private static String customerName(Map<UUID, OrderMapper.CustomerSummaryRow> customers, UUID customerId) {
        OrderMapper.CustomerSummaryRow customer = customerId == null ? null : customers.get(customerId);
        return customer == null ? null : customer.name();
    }

    private void applySort(LambdaQueryWrapper<Order> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByAsc(Order::getDueDate).orderByAsc(Order::getOrderNo);
            return;
        }
        String[] tokens = sort.split(",");
        boolean applied = false;
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            if (applySortField(wrapper, tokens[i].trim(), tokens[i + 1].trim())) {
                applied = true;
            }
        }
        if (!applied) {
            wrapper.orderByAsc(Order::getDueDate).orderByAsc(Order::getOrderNo);
        }
    }

    private boolean applySortField(LambdaQueryWrapper<Order> wrapper, String field, String direction) {
        boolean ascending = !"desc".equalsIgnoreCase(direction);
        switch (field) {
            case "orderNo":
                wrapper.orderBy(true, ascending, Order::getOrderNo);
                return true;
            case "dueDate":
                wrapper.orderBy(true, ascending, Order::getDueDate);
                return true;
            case "priority":
                wrapper.orderBy(true, ascending, Order::getPriority);
                return true;
            case "status":
                wrapper.orderBy(true, ascending, Order::getStatus);
                return true;
            case "qty":
                wrapper.orderBy(true, ascending, Order::getQty);
                return true;
            case "customerId":
                wrapper.orderBy(true, ascending, Order::getCustomerId);
                return true;
            case "createdAt":
                wrapper.orderBy(true, ascending, Order::getCreatedAt);
                return true;
            case "updatedAt":
                wrapper.orderBy(true, ascending, Order::getUpdatedAt);
                return true;
            default:
                return false;
        }
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
