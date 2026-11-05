package com.hengce.aps.service.masterdata.order;

import com.hengce.aps.domain.model.FreezeStatus;
import com.hengce.aps.domain.model.OrderStatus;
import com.hengce.aps.infrastructure.persistence.order.Order;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订单返回视图，字段与 orders.yaml 的 Order 模式一致（不暴露内部主键与租户字段）。
 *
 * @param orderNo 订单业务编码
 * @param materialCode 产品物料编码（orders.product_id → material.code）
 * @param qty 数量
 * @param status 订单状态
 * @param freezeStatus 冻结状态
 * @param dueDate 交期
 * @param priority 优先级
 * @param source 订单来源
 * @param customerId 客户主键，可为空
 * @param customerCode 客户编码（C50，只读），客户缺失或已软删除时为空
 * @param customerName 客户名称（C50，只读），与 customerCode 成对为空
 * @param routingId 指定工艺路线主键，可为空
 * @param remark 备注
 * @param extAttrs 扩展属性
 * @param freezeLock 冻结锁定信息（C23 锁窗/锁资源），非锁定态为空
 * @param version 乐观锁版本
 * @param createdAt 创建时间（UTC）
 * @param updatedAt 更新时间（UTC）
 */
public record OrderView(
        String orderNo,
        String materialCode,
        BigDecimal qty,
        OrderStatus status,
        FreezeStatus freezeStatus,
        LocalDate dueDate,
        OrderPriority priority,
        OrderSource source,
        UUID customerId,
        String customerCode,
        String customerName,
        UUID routingId,
        String remark,
        Map<String, Object> extAttrs,
        FreezeLock freezeLock,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * {@code orders.ext_attrs} 中冻结锁定信息的键名。
     */
    public static final String FREEZE_LOCK_ATTRIBUTE = "freezeLock";

    /**
     * 由持久化实体转换。
     *
     * @param order 订单实体
     * @param materialCode 产品物料编码，可为空（物料已删除时）
     * @param customerCode 客户编码（C50），可为空
     * @param customerName 客户名称（C50），可为空
     * @return 返回视图
     */
    public static OrderView from(Order order, String materialCode, String customerCode, String customerName) {
        return new OrderView(
                order.getOrderNo(),
                materialCode,
                order.getQty(),
                OrderStatus.valueOf(order.getStatus()),
                FreezeStatus.valueOf(order.getFreezeStatus()),
                order.getDueDate(),
                OrderPriority.valueOf(order.getPriority()),
                OrderSource.valueOf(order.getSource()),
                order.getCustomerId(),
                customerCode,
                customerName,
                order.getRoutingId(),
                order.getRemark(),
                order.getExtAttrs(),
                parseFreezeLock(order.getExtAttrs()),
                order.getVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    /**
     * 从 {@code orders.ext_attrs} 解析冻结锁定信息。
     *
     * @param attributes 扩展属性，可为空
     * @return 锁定信息；非锁定态或字段缺失时返回 null
     */
    public static FreezeLock parseFreezeLock(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        Object raw = attributes.get(FREEZE_LOCK_ATTRIBUTE);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        OffsetDateTime from = parseTime(map.get("from"));
        OffsetDateTime to = parseTime(map.get("to"));
        List<String> codes = parseCodes(map.get("resourceCodes"));
        if (from == null && to == null && codes.isEmpty()) {
            return null;
        }
        return new FreezeLock(from, to, codes);
    }

    private static OffsetDateTime parseTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static List<String> parseCodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .toList();
    }
}
