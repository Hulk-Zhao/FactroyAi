package com.hengce.aps.infrastructure.persistence.strategy;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 策略规则明细持久化实体，字段对齐 V1 迁移的 strategy_rule 表。
 *
 * <p>{@code (strategyId, ruleCode)} 在 deleted=false 范围内唯一。</p>
 */
@TableName(value = "strategy_rule", autoResultMap = true)
public class StrategyRule extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 所属策略主键。 */
    @TableField("strategy_id")
    private UUID strategyId;

    /** 规则编码，必须是白名单编码。 */
    @TableField("rule_code")
    private String ruleCode;

    /** 规则名称。 */
    @TableField("name")
    private String name;

    /** 规则阶段：INITIAL / OPTIMIZATION。 */
    @TableField("rule_phase")
    private String rulePhase;

    /** 规则类型：HARD / SOFT。 */
    @TableField("rule_type")
    private String ruleType;

    /** 权重，M2 的 8 条软约束权重之和为 100。 */
    @TableField("weight")
    private BigDecimal weight;

    /** 规则参数对象。 */
    @TableField(value = "params", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> params = new LinkedHashMap<>();

    /** 是否启用。 */
    @TableField("enabled")
    private Boolean enabled;

    /** 排序号。 */
    @TableField("seq_no")
    private Integer seqNo;

    public UUID getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(UUID strategyId) {
        this.strategyId = strategyId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRulePhase() {
        return rulePhase;
    }

    public void setRulePhase(String rulePhase) {
        this.rulePhase = rulePhase;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params == null ? new LinkedHashMap<>() : params;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }
}
