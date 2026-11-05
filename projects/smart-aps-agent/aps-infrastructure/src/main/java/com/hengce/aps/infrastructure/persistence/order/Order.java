package com.hengce.aps.infrastructure.persistence.order;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 订单持久化实体，字段对齐 V1 迁移的 {@code orders} 表与 orders.yaml 的 Order 模式。
 *
 * <p>{@code status} / {@code freeze_status} / {@code source} / {@code priority} 以 VARCHAR + CHECK
 * 落库，实体保持字符串，由服务层统一转换为枚举，保持持久层不依赖业务枚举。</p>
 *
 * <p>{@code product_id} 指向 {@code material.id}；对外响应中的 {@code materialCode} 由服务层
 * 通过 material 表回填，不是 orders 表列。</p>
 */
@TableName(value = "orders", autoResultMap = true)
public class Order extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 订单业务编码，租户内唯一（uq_orders_tenant_order_no）。 */
    @TableField("order_no")
    private String orderNo;

    /** 订单来源 ERP_SYNC/MANUAL/AGENT。 */
    @TableField("source")
    private String source;

    /** 产品物料主键（material.id）。 */
    @TableField("product_id")
    private UUID productId;

    /** 订单数量。 */
    @TableField("qty")
    private BigDecimal qty;

    /** 交期。 */
    @TableField("due_date")
    private LocalDate dueDate;

    /** 优先级 URGENT/HIGH/NORMAL/LOW。 */
    @TableField("priority")
    private String priority;

    /** 客户主键，可为空。 */
    @TableField("customer_id")
    private UUID customerId;

    /** 指定工艺路线主键，可为空；为空时确认按产品取唯一 ACTIVE。 */
    @TableField("routing_id")
    private UUID routingId;

    /** 订单状态 DRAFT/CONFIRMED/...。 */
    @TableField("status")
    private String status;

    /** 冻结状态 UNFROZEN/FROZEN/LOCKED_*，与 status 为两条独立状态轴。 */
    @TableField("freeze_status")
    private String freezeStatus;

    /** 备注。 */
    @TableField("remark")
    private String remark;

    /** 扩展属性（JSONB object）。 */
    @TableField(value = "ext_attrs", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> extAttrs = new LinkedHashMap<>();

    /** 产品物料编码，非表列，仅查询/响应回填。 */
    @TableField(exist = false)
    private String materialCode;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getRoutingId() {
        return routingId;
    }

    public void setRoutingId(UUID routingId) {
        this.routingId = routingId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFreezeStatus() {
        return freezeStatus;
    }

    public void setFreezeStatus(String freezeStatus) {
        this.freezeStatus = freezeStatus;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Map<String, Object> getExtAttrs() {
        return extAttrs;
    }

    public void setExtAttrs(Map<String, Object> extAttrs) {
        this.extAttrs = extAttrs;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }
}
