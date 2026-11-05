package com.hengce.aps.infrastructure.persistence.process.routing;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.util.UUID;

/**
 * 工艺路线依赖边持久化实体，字段对齐 V1__init.sql 的 routing_edge 表。
 *
 * <p>边在库内保存节点 UUID（from_node_id/to_node_id），对外接口使用 seqNo；
 * seqNo↔UUID 的映射由服务层保存/读取时完成（契约 C15 20.2）。</p>
 */
@TableName("routing_edge")
public class RoutingEdge extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 所属工艺路线。 */
    @TableField("routing_id")
    private UUID routingId;

    /** 前序节点主键。 */
    @TableField("from_node_id")
    private UUID fromNodeId;

    /** 后继节点主键。 */
    @TableField("to_node_id")
    private UUID toNodeId;

    /** 依赖类型 FS/SS/FF。 */
    @TableField("depend_type")
    private String dependType;

    /** 滞后分钟，非负。 */
    @TableField("lag_minutes")
    private Integer lagMinutes;

    public UUID getRoutingId() {
        return routingId;
    }

    public void setRoutingId(UUID routingId) {
        this.routingId = routingId;
    }

    public UUID getFromNodeId() {
        return fromNodeId;
    }

    public void setFromNodeId(UUID fromNodeId) {
        this.fromNodeId = fromNodeId;
    }

    public UUID getToNodeId() {
        return toNodeId;
    }

    public void setToNodeId(UUID toNodeId) {
        this.toNodeId = toNodeId;
    }

    public String getDependType() {
        return dependType;
    }

    public void setDependType(String dependType) {
        this.dependType = dependType;
    }

    public Integer getLagMinutes() {
        return lagMinutes;
    }

    public void setLagMinutes(Integer lagMinutes) {
        this.lagMinutes = lagMinutes;
    }
}
