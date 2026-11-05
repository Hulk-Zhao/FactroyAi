package com.hengce.aps.infrastructure.persistence.process.routing;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工艺路线节点持久化实体，字段对齐 V1__init.sql 的 routing_node 表。
 *
 * <p>关键事实（契约 C15 20.2）：V1 的 routing_node <strong>没有</strong>
 * {@code primary_resource_id} 列。节点级资源覆盖写入 {@code overrides} JSONB：
 * {@code overrides.primaryResourceId} 为 UUID，{@code overrides.secondaryResourceIds}
 * 为 UUID 数组；未覆盖时由消费方回退 {@code operation_template.primary_resource_id}。</p>
 */
@TableName(value = "routing_node", autoResultMap = true)
public class RoutingNode extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 所属工艺路线。 */
    @TableField("routing_id")
    private UUID routingId;

    /** 节点稳定标识，路线内唯一（uq_routing_node_key）；规则见服务层 nodeKey(seqNo)。 */
    @TableField("node_key")
    private String nodeKey;

    /** 引用的工序模版。 */
    @TableField("operation_template_id")
    private UUID operationTemplateId;

    /** 工序编码，可空。 */
    @TableField("operation_code")
    private String operationCode;

    /** 工序名称，可空。 */
    @TableField("operation_name")
    private String operationName;

    /** 标准加工秒数，可空。 */
    @TableField("standard_seconds")
    private Integer standardSeconds;

    /** 换型秒数，可空。 */
    @TableField("setup_seconds")
    private Integer setupSeconds;

    /** 收尾秒数，可空。 */
    @TableField("teardown_seconds")
    private Integer teardownSeconds;

    /** 是否可选工序。 */
    @TableField("is_optional")
    private Boolean isOptional;

    /** 覆盖参数，含 primaryResourceId / secondaryResourceIds 等。 */
    @TableField("overrides")
    private Map<String, Object> overrides = new LinkedHashMap<>();

    /** 画布横坐标。 */
    @TableField("position_x")
    private BigDecimal positionX;

    /** 画布纵坐标。 */
    @TableField("position_y")
    private BigDecimal positionY;

    /** 路线内唯一序号。 */
    @TableField("seq_no")
    private Integer seqNo;

    public UUID getRoutingId() {
        return routingId;
    }

    public void setRoutingId(UUID routingId) {
        this.routingId = routingId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public UUID getOperationTemplateId() {
        return operationTemplateId;
    }

    public void setOperationTemplateId(UUID operationTemplateId) {
        this.operationTemplateId = operationTemplateId;
    }

    public String getOperationCode() {
        return operationCode;
    }

    public void setOperationCode(String operationCode) {
        this.operationCode = operationCode;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public Integer getStandardSeconds() {
        return standardSeconds;
    }

    public void setStandardSeconds(Integer standardSeconds) {
        this.standardSeconds = standardSeconds;
    }

    public Integer getSetupSeconds() {
        return setupSeconds;
    }

    public void setSetupSeconds(Integer setupSeconds) {
        this.setupSeconds = setupSeconds;
    }

    public Integer getTeardownSeconds() {
        return teardownSeconds;
    }

    public void setTeardownSeconds(Integer teardownSeconds) {
        this.teardownSeconds = teardownSeconds;
    }

    public Boolean getIsOptional() {
        return isOptional;
    }

    public void setIsOptional(Boolean isOptional) {
        this.isOptional = isOptional;
    }

    public Map<String, Object> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, Object> overrides) {
        this.overrides = overrides;
    }

    public BigDecimal getPositionX() {
        return positionX;
    }

    public void setPositionX(BigDecimal positionX) {
        this.positionX = positionX;
    }

    public BigDecimal getPositionY() {
        return positionY;
    }

    public void setPositionY(BigDecimal positionY) {
        this.positionY = positionY;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }
}
