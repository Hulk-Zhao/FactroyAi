package com.hengce.aps.infrastructure.persistence.process;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import java.util.List;
import java.util.UUID;

/**
 * 可复用工序模版，字段与 db/migration/V1__init.sql 的 operation_template 表一一对应。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>{@code templateVersion} 为业务版本号，{@code version}（继承自 {@link BaseEntity}）为乐观锁版本号。</li>
 *   <li>{@code secondaryResourceIds} / {@code inputMaterialIds} 对应 JSONB 数组列，由持久层 XML 的
 *       typeHandler 负责序列化。</li>
 *   <li>工序模版的唯一键为 {@code tenantId + code + templateVersion}，与
 *       {@code uq_operation_template_code_version} 保持一致。</li>
 * </ul>
 */
@TableName("operation_template")
public class OperationTemplate extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 模版编码，租户内按业务版本唯一。
     */
    @TableField("code")
    private String code;

    /**
     * 模版名称。
     */
    @TableField("name")
    private String name;

    /**
     * 主资源标识。
     */
    @TableField("primary_resource_id")
    private UUID primaryResourceId;

    /**
     * 主资源组标识。
     */
    @TableField("primary_resource_group_id")
    private UUID primaryResourceGroupId;

    /**
     * 辅助资源标识数组，对应 JSONB array。
     */
    @TableField("secondary_resource_ids")
    private List<UUID> secondaryResourceIds;

    /**
     * 输入物料标识数组，对应 JSONB array。
     */
    @TableField("input_material_ids")
    private List<UUID> inputMaterialIds;

    /**
     * 产出物料标识。
     */
    @TableField("output_material_id")
    private UUID outputMaterialId;

    /**
     * 单件标准工时（秒）。
     */
    @TableField("standard_seconds")
    private Integer standardSeconds;

    /**
     * 换型/准备工时（秒）。
     */
    @TableField("setup_seconds")
    private Integer setupSeconds;

    /**
     * 收尾/拆卸工时（秒）。
     */
    @TableField("teardown_seconds")
    private Integer teardownSeconds;

    /**
     * 业务版本号，修改生成新版本，历史路线仍可引用旧版本。
     */
    @TableField("template_version")
    private Integer templateVersion;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getPrimaryResourceId() {
        return primaryResourceId;
    }

    public void setPrimaryResourceId(UUID primaryResourceId) {
        this.primaryResourceId = primaryResourceId;
    }

    public UUID getPrimaryResourceGroupId() {
        return primaryResourceGroupId;
    }

    public void setPrimaryResourceGroupId(UUID primaryResourceGroupId) {
        this.primaryResourceGroupId = primaryResourceGroupId;
    }

    public List<UUID> getSecondaryResourceIds() {
        return secondaryResourceIds;
    }

    public void setSecondaryResourceIds(List<UUID> secondaryResourceIds) {
        this.secondaryResourceIds = secondaryResourceIds;
    }

    public List<UUID> getInputMaterialIds() {
        return inputMaterialIds;
    }

    public void setInputMaterialIds(List<UUID> inputMaterialIds) {
        this.inputMaterialIds = inputMaterialIds;
    }

    public UUID getOutputMaterialId() {
        return outputMaterialId;
    }

    public void setOutputMaterialId(UUID outputMaterialId) {
        this.outputMaterialId = outputMaterialId;
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

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(Integer templateVersion) {
        this.templateVersion = templateVersion;
    }
}
