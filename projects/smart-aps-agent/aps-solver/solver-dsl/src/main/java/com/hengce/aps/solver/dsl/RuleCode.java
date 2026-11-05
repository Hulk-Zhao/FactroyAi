package com.hengce.aps.solver.dsl;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M2 受控 DSL 的白名单规则编码（PRD FR-SS-002 的 8 条核心软约束）。
 *
 * <p>约束（C19）：</p>
 * <ul>
 *   <li>DSL 与策略规则只允许本枚举内的 rule_code，其它编码一律 42202；</li>
 *   <li>8 条规则权重之和必须精确等于 {@link #TOTAL_WEIGHT}；</li>
 *   <li>每条规则只接受本枚举声明的参数键，其它键一律 42204；</li>
 *   <li>默认权重之和等于 {@link #TOTAL_WEIGHT}，新建策略不传规则时使用默认值。</li>
 * </ul>
 */
public enum RuleCode {

    /** 最小超期：按超期时长加权惩罚。 */
    MIN_TARDINESS(20, "最小超期", Set.of("unit")),

    /** 交期优先（Earliest Due Date）：同一资源上先排交期更早的任务。 */
    EDD(15, "交期优先", Set.of()),

    /** 短任务优先（Shortest Processing Time）：同一资源上先排加工时长更短的任务。 */
    SPT(10, "短任务优先", Set.of()),

    /** 同品连续生产：同一资源上相邻任务尽量同产品族。 */
    SAME_PRODUCT_CONTINUOUS(10, "同品连续生产", Set.of("groupBy")),

    /** 最小换型时长：按换型秒数加权惩罚。 */
    MIN_SETUP_TIME(10, "最小换型时长", Set.of("unit")),

    /** 均衡负载率：按资源负载平方和惩罚，固定总量下最小化即均衡。 */
    BALANCE_LOAD(10, "均衡负载率", Set.of("metric")),

    /** 优先级尊重：同一资源上高优先级任务不应排在低优先级之后。 */
    PRIORITY_RESPECT(15, "优先级尊重", Set.of("maxPriority")),

    /** 最小总完工时间（Makespan）：按最后完工-最早开始加权惩罚。 */
    MIN_MAKESPAN(10, "最小总完工时间", Set.of("unit"));

    /**
     * 8 条规则权重之和的强制值（C19）。
     */
    public static final int TOTAL_WEIGHT = 100;

    /**
     * 单条规则权重上下限（BR-SS-002-02）。
     */
    public static final int MIN_WEIGHT = 0;

    /**
     * 单条规则权重上限。
     */
    public static final int MAX_WEIGHT = 100;

    /**
     * 默认规则数量。
     */
    public static final int CORE_RULE_COUNT = 8;

    private static final Map<String, RuleCode> BY_CODE = new LinkedHashMap<>();

    static {
        for (RuleCode ruleCode : values()) {
            BY_CODE.put(ruleCode.name(), ruleCode);
        }
    }

    private final int defaultWeight;
    private final String displayName;
    private final Set<String> allowedParams;

    RuleCode(int defaultWeight, String displayName, Set<String> allowedParams) {
        this.defaultWeight = defaultWeight;
        this.displayName = displayName;
        this.allowedParams = allowedParams;
    }

    /**
     * 默认权重。
     *
     * @return 默认权重
     */
    public int getDefaultWeight() {
        return defaultWeight;
    }

    /**
     * 中文名称。
     *
     * @return 名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 允许的参数字段名。
     *
     * @return 参数名集合
     */
    public Set<String> getAllowedParams() {
        return allowedParams;
    }

    /**
     * 按编码精确查找（区分大小写，必须是白名单内的编码）。
     *
     * @param code 规则编码
     * @return 命中的规则；未命中为空
     */
    public static Optional<RuleCode> fromCode(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code.trim()));
    }

    /**
     * 是否为白名单编码。
     *
     * @param code 规则编码
     * @return {@code true} 表示白名单内
     */
    public static boolean isSupported(String code) {
        return fromCode(code).isPresent();
    }

    /**
     * 全部白名单编码（按声明顺序）。
     *
     * @return 编码列表
     */
    public static List<String> codes() {
        return List.copyOf(BY_CODE.keySet());
    }

    /**
     * 默认权重映射（8 条、合计 100）。
     *
     * @return 编码到默认权重
     */
    public static Map<String, Integer> defaultWeights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (RuleCode ruleCode : values()) {
            weights.put(ruleCode.name(), ruleCode.defaultWeight);
        }
        return weights;
    }

    /**
     * 全部参数键的并集，供错误信息使用。
     *
     * @return 参数键集合
     */
    public static Set<String> allParamKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (RuleCode ruleCode : values()) {
            keys.addAll(ruleCode.allowedParams);
        }
        return keys;
    }
}
