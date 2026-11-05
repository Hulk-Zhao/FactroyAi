package com.hengce.aps.infrastructure.persistence.process.routing;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工艺路线主表持久化实体，字段对齐 V1__init.sql 的 routing 表。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code routingVersion} 为业务版本号（字符串，如 V1.0）；继承自 {@link BaseEntity}
 *       的 {@code version} 为乐观锁整数，两者语义不同（见契约 C15 20.1）。</li>
 *   <li>{@code nodes}/{@code edges} 为前端 DAG 渲染快照，保存在 JSONB 列；事实来源仍是
 *       {@code routing_node}/{@code routing_edge}（见 schema.md 5.5）。</li>
 *   <li>{@code productCode} 不是 routing 表列，由列表/详情查询 join material 回填。</li>
 * </ul>
 */
@TableName(value = "routing", autoResultMap = true)
public class Routing extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 产品物料主键。 */
    @TableField("product_id")
    private UUID productId;

    /** 业务版本号，与 product_id 联合唯一（uq_routing_product_version）。 */
    @TableField("routing_version")
    private String routingVersion;

    /** 生效日期。 */
    @TableField("effective_date")
    private LocalDate effectiveDate;

    /** 失效日期，可为空。 */
    @TableField("expiry_date")
    private LocalDate expiryDate;

    /** 状态 DRAFT/ACTIVE/INACTIVE。 */
    @TableField("status")
    private String status;

    /** 是否拼板路线。 */
    @TableField("is_panel")
    private Boolean isPanel;

    /** 拼板尺寸。 */
    @TableField("panel_size")
    private Integer panelSize;

    /** 节点快照（JSONB array）。 */
    @TableField("nodes")
    private List<Map<String, Object>> nodes;

    /** 边快照（JSONB array）。 */
    @TableField("edges")
    private List<Map<String, Object>> edges;

    /** 产品物料编码，非表列，仅查询回填。 */
    @TableField(exist = false)
    private String productCode;

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getRoutingVersion() {
        return routingVersion;
    }

    public void setRoutingVersion(String routingVersion) {
        this.routingVersion = routingVersion;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getIsPanel() {
        return isPanel;
    }

    public void setIsPanel(Boolean isPanel) {
        this.isPanel = isPanel;
    }

    public Integer getPanelSize() {
        return panelSize;
    }

    public void setPanelSize(Integer panelSize) {
        this.panelSize = panelSize;
    }

    public List<Map<String, Object>> getNodes() {
        return nodes;
    }

    public void setNodes(List<Map<String, Object>> nodes) {
        this.nodes = nodes;
    }

    public List<Map<String, Object>> getEdges() {
        return edges;
    }

    public void setEdges(List<Map<String, Object>> edges) {
        this.edges = edges;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }
}
