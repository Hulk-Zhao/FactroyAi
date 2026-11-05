package com.hengce.aps.infrastructure.persistence.strategy;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 排产策略持久化实体，字段对齐 V1 迁移的 strategy 表。
 *
 * <p>{@code strategyVersion} 为业务版本，{@code version}（继承自 {@link BaseEntity}）为乐观锁版本；
 * 规则明细以 {@link StrategyRule} 为准，{@code optimizationRules} 仅保留权重摘要快照。</p>
 */
@TableName(value = "strategy", autoResultMap = true)
public class Strategy extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 策略名称。 */
    @TableField("name")
    private String name;

    /** 排产方向：FORWARD / BACKWARD / BOTTLENECK。 */
    @TableField("direction")
    private String direction;

    /** 业务版本号。 */
    @TableField("strategy_version")
    private Integer strategyVersion;

    /** 初排规则对象。 */
    @TableField(value = "initial_rules", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> initialRules = new LinkedHashMap<>();

    /** 优化规则权重摘要对象。 */
    @TableField(value = "optimization_rules", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> optimizationRules = new LinkedHashMap<>();

    /** 生效日期。 */
    @TableField("effective_from")
    private LocalDate effectiveFrom;

    /** 失效日期，可空。 */
    @TableField("effective_to")
    private LocalDate effectiveTo;

    /** 适用条件对象。 */
    @TableField(value = "apply_condition", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> applyCondition = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public Integer getStrategyVersion() {
        return strategyVersion;
    }

    public void setStrategyVersion(Integer strategyVersion) {
        this.strategyVersion = strategyVersion;
    }

    public Map<String, Object> getInitialRules() {
        return initialRules;
    }

    public void setInitialRules(Map<String, Object> initialRules) {
        this.initialRules = initialRules == null ? new LinkedHashMap<>() : initialRules;
    }

    public Map<String, Object> getOptimizationRules() {
        return optimizationRules;
    }

    public void setOptimizationRules(Map<String, Object> optimizationRules) {
        this.optimizationRules = optimizationRules == null ? new LinkedHashMap<>() : optimizationRules;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Map<String, Object> getApplyCondition() {
        return applyCondition;
    }

    public void setApplyCondition(Map<String, Object> applyCondition) {
        this.applyCondition = applyCondition == null ? new LinkedHashMap<>() : applyCondition;
    }
}
