package com.hengce.aps.infrastructure.persistence.strategy;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 约束 DSL 版本持久化实体，字段对齐 V4 迁移的 constraint_dsl 表。
 *
 * <p>同一 {@code (tenantId, code)} 只允许一个 {@code ACTIVE}（部分唯一索引保证）；
 * {@code rollbackOf} 记录回滚来源版本。</p>
 */
@TableName(value = "constraint_dsl", autoResultMap = true)
public class ConstraintDsl extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** DSL 标识，同一标识下多版本。 */
    @TableField("code")
    private String code;

    /** 版本号，从 1 开始递增。 */
    @TableField("version_no")
    private Integer versionNo;

    /** 规范化的 YAML 文本（数组形式）。 */
    @TableField("yaml_text")
    private String yamlText;

    /** 编译后的约束快照列表。 */
    @TableField(value = "compiled_spec", typeHandler = JsonbTypeHandler.class)
    private List<Map<String, Object>> compiledSpec = new ArrayList<>();

    /** 状态：DRAFT / ACTIVE / ROLLBACK。 */
    @TableField("status")
    private String status;

    /** YAML 的 SHA-256 校验和。 */
    @TableField("checksum")
    private String checksum;

    /** 回滚来源版本主键。 */
    @TableField("rollback_of")
    private UUID rollbackOf;

    /** 生效时刻。 */
    @TableField("effective_from")
    private OffsetDateTime effectiveFrom;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getYamlText() {
        return yamlText;
    }

    public void setYamlText(String yamlText) {
        this.yamlText = yamlText;
    }

    public List<Map<String, Object>> getCompiledSpec() {
        return compiledSpec;
    }

    public void setCompiledSpec(List<Map<String, Object>> compiledSpec) {
        this.compiledSpec = compiledSpec == null ? new ArrayList<>() : compiledSpec;
    }

    /**
     * 编译快照的原始 Map 形态，便于服务层序列化。
     *
     * @return 可变副本
     */
    public List<Map<String, Object>> compiledSpecCopy() {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : compiledSpec) {
            copy.add(new LinkedHashMap<>(item));
        }
        return copy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public UUID getRollbackOf() {
        return rollbackOf;
    }

    public void setRollbackOf(UUID rollbackOf) {
        this.rollbackOf = rollbackOf;
    }

    public OffsetDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(OffsetDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }
}
