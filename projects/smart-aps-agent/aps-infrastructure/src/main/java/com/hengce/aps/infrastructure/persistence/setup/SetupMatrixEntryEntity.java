package com.hengce.aps.infrastructure.persistence.setup;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;

import java.util.UUID;

/**
 * 换型矩阵持久化实体，字段对齐 V14 迁移的 {@code setup_matrix_entry} 表与 C44。
 *
 * <p>主键沿用 {@link BaseEntity} 的 {@code IdType.INPUT}，UUID 由服务层显式生成。
 * {@code scope_type} 与 {@code resource_id}/{@code resource_group_id} 由服务层保持一致，
 * 数据库 CHECK 约束兜底。</p>
 */
@TableName("setup_matrix_entry")
public class SetupMatrixEntryEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 矩阵维度：MATERIAL_CODE（产品）/ OPERATION_CODE（程序）。 */
    @TableField("key_type")
    private String keyType;

    /** 前序任务维度键；空表示通配。 */
    @TableField("from_key")
    private String fromKey;

    /** 当前任务维度键；空表示通配。 */
    @TableField("to_key")
    private String toKey;

    /** 生效范围：TENANT / RESOURCE_GROUP / RESOURCE。 */
    @TableField("scope_type")
    private String scopeType;

    /** 资源主键；RESOURCE 范围必填。 */
    @TableField("resource_id")
    private UUID resourceId;

    /** 资源组主键；RESOURCE_GROUP 范围必填。 */
    @TableField("resource_group_id")
    private UUID resourceGroupId;

    /** 默认换型秒数。 */
    @TableField("setup_seconds")
    private Integer setupSeconds;

    /** 同系列特例秒数；空表示回退默认值。 */
    @TableField("same_series_setup_seconds")
    private Integer sameSeriesSetupSeconds;

    /** 跨系列特例秒数；空表示回退默认值。 */
    @TableField("cross_series_setup_seconds")
    private Integer crossSeriesSetupSeconds;

    /** 备注。 */
    @TableField("remark")
    private String remark;

    public String getKeyType() {
        return keyType;
    }

    public void setKeyType(String keyType) {
        this.keyType = keyType;
    }

    public String getFromKey() {
        return fromKey;
    }

    public void setFromKey(String fromKey) {
        this.fromKey = fromKey;
    }

    public String getToKey() {
        return toKey;
    }

    public void setToKey(String toKey) {
        this.toKey = toKey;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public void setResourceId(UUID resourceId) {
        this.resourceId = resourceId;
    }

    public UUID getResourceGroupId() {
        return resourceGroupId;
    }

    public void setResourceGroupId(UUID resourceGroupId) {
        this.resourceGroupId = resourceGroupId;
    }

    public Integer getSetupSeconds() {
        return setupSeconds;
    }

    public void setSetupSeconds(Integer setupSeconds) {
        this.setupSeconds = setupSeconds;
    }

    public Integer getSameSeriesSetupSeconds() {
        return sameSeriesSetupSeconds;
    }

    public void setSameSeriesSetupSeconds(Integer sameSeriesSetupSeconds) {
        this.sameSeriesSetupSeconds = sameSeriesSetupSeconds;
    }

    public Integer getCrossSeriesSetupSeconds() {
        return crossSeriesSetupSeconds;
    }

    public void setCrossSeriesSetupSeconds(Integer crossSeriesSetupSeconds) {
        this.crossSeriesSetupSeconds = crossSeriesSetupSeconds;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
