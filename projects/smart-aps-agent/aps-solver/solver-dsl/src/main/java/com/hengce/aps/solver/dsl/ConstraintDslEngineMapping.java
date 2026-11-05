package com.hengce.aps.solver.dsl;

import com.hengce.aps.solver.api.ConstraintSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * DSL 规则到求解引擎能力的映射与支持矩阵。
 *
 * <p>求解引擎（Timefold）只实现了 8 条软约束的固定语义，部分 DSL 白名单参数在引擎内
 * <b>没有对应实现</b>。本类负责把「引擎能真正执行的语义」与「引擎做不到的语义」区分开：
 * 做不到的参数不能被静默忽略，必须由激活/装配链路以 {@code 42200} 拒绝并点名规则。</p>
 *
 * <p>支持矩阵（规则级 8/8 可映射，参数级按值判定）：</p>
 * <pre>
 * | 规则码                  | 引擎约束                                     | 支持的参数                         | 不支持的参数（激活即拒绝）              |
 * |-------------------------|----------------------------------------------|------------------------------------|------------------------------------------|
 * | MIN_TARDINESS           | MIN_TARDINESS（超期分钟加权）                | unit 缺省/MINUTE                   | unit=SECOND/HOUR                         |
 * | EDD                     | EDD（相邻任务交期逆序惩罚）                  | 无                                 | 任意参数                                 |
 * | SPT                     | SPT（相邻任务加工时长逆序惩罚）              | 无                                 | 任意参数                                 |
 * | SAME_PRODUCT_CONTINUOUS | SAME_PRODUCT_CONTINUOUS（相邻产品族不同惩罚）| groupBy 缺省/PRODUCT_FAMILY        | groupBy=MATERIAL                         |
 * | MIN_SETUP_TIME          | MIN_SETUP_TIME（换型分钟加权，向上取整）     | unit 缺省/MINUTE                   | unit=SECOND/HOUR                         |
 * | BALANCE_LOAD            | BALANCE_LOAD（资源加工秒数平方和）           | metric 缺省/LOAD_SECONDS           | metric=TASK_COUNT                        |
 * | PRIORITY_RESPECT        | PRIORITY_RESPECT（相邻优先级逆序惩罚）       | 无（引擎按档位相对顺序）           | maxPriority（引擎无阈值裁剪语义）        |
 * | MIN_MAKESPAN            | MIN_MAKESPAN（最晚完工-最早开始分钟加权）    | unit 缺省/MINUTE                   | unit=SECOND/HOUR                         |
 * </pre>
 *
 * <p>说明：引擎约束名与 {@link RuleCode} 编码一致，因此映射表不需要第二套编码；
 * {@code params} 只做“能否被引擎忠实执行”的判定，不改变引擎的固定语义。</p>
 */
public final class ConstraintDslEngineMapping {

    /**
     * 引擎只支持的生效阶段。
     */
    private static final String PHASE_OPTIMIZATION = "OPTIMIZATION";

    /**
     * 引擎只支持的约束类型。
     */
    private static final String TYPE_SOFT = "SOFT";

    /**
     * 分钟单位（引擎统一按分钟计罚）。
     */
    private static final String UNIT_MINUTE = "MINUTE";

    /**
     * 按产品族分组（引擎任务上的 productFamily 来源于物料分类编码）。
     */
    private static final String GROUP_BY_PRODUCT_FAMILY = "PRODUCT_FAMILY";

    /**
     * 按加工秒数计算负载（引擎 BALANCE_LOAD 的固定口径）。
     */
    private static final String METRIC_LOAD_SECONDS = "LOAD_SECONDS";

    private ConstraintDslEngineMapping() {
    }

    /**
     * 映射结果。
     *
     * @param problems 无法被引擎忠实执行的问题列表；空表示全部可接入
     * @param weights 规则编码到权重；仅在 {@link #problems} 为空时有值
     */
    public record Analysis(List<String> problems, Map<String, Integer> weights) {

        /**
         * 是否全部规则都能被引擎执行。
         *
         * @return {@code true} 表示可接入
         */
        public boolean supported() {
            return problems.isEmpty();
        }
    }

    /**
     * 把 {@code constraint_dsl.compiled_spec} 的原始 JSONB 结构还原为引擎中立 DTO。
     *
     * <p>只做结构还原，不做支持性判定；结构异常抛出 {@link IllegalArgumentException}，
     * 由调用方转换为 {@code 42200}。</p>
     *
     * @param raw compiled_spec 原始数组
     * @return 约束快照列表
     */
    public static List<ConstraintSpec> fromCompiledSpec(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("compiled_spec 为空，无法接入求解引擎");
        }
        List<ConstraintSpec> specs = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<String, Object> item = raw.get(index);
            if (item == null) {
                throw new IllegalArgumentException("compiled_spec[" + index + "] 为空");
            }
            Object code = item.get("code");
            if (code == null || String.valueOf(code).isBlank()) {
                throw new IllegalArgumentException("compiled_spec[" + index + "] 缺少 code");
            }
            specs.add(new ConstraintSpec(
                    String.valueOf(code).trim(),
                    item.get("type") == null ? TYPE_SOFT : String.valueOf(item.get("type")),
                    toBigDecimal(item.get("weight"), index),
                    Boolean.TRUE.equals(item.get("hard")),
                    item.get("phase") == null ? PHASE_OPTIMIZATION : String.valueOf(item.get("phase")),
                    toParams(item.get("params"), index)));
        }
        return List.copyOf(specs);
    }

    /**
     * 判定一组约束能否被引擎忠实执行。
     *
     * <p>判定包含：8 条规则齐全且不重复、类型为 SOFT、非硬约束、阶段为 OPTIMIZATION、
     * 权重为 0..100 的整数且合计 100、参数取值在引擎实现范围内。</p>
     *
     * @param specs 约束快照
     * @return 分析结果
     */
    public static Analysis analyze(List<ConstraintSpec> specs) {
        List<String> problems = new ArrayList<>();
        Map<String, ConstraintSpec> byCode = new LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            problems.add("约束列表为空，无法接入求解引擎");
            return new Analysis(List.copyOf(problems), Map.of());
        }
        for (ConstraintSpec spec : specs) {
            if (spec == null) {
                problems.add("存在空约束项");
                continue;
            }
            String code = spec.code() == null ? "" : spec.code().trim();
            if (!RuleCode.isSupported(code)) {
                problems.add("规则 " + describe(code) + " 不在白名单，引擎无对应约束");
                continue;
            }
            if (byCode.containsKey(code)) {
                problems.add("规则 " + code + " 重复声明，引擎无法合并两套语义");
                continue;
            }
            byCode.put(code, spec);
            if (!TYPE_SOFT.equalsIgnoreCase(spec.type())) {
                problems.add("规则 " + code + " 的 type=" + spec.type() + "，引擎只支持 SOFT 规则");
            }
            if (spec.hard()) {
                problems.add("规则 " + code + " 声明为硬约束，DSL 不允许接入硬约束");
            }
            if (spec.phase() != null && !PHASE_OPTIMIZATION.equalsIgnoreCase(spec.phase())) {
                problems.add("规则 " + code + " 的 phase=" + spec.phase() + "，引擎只在 OPTIMIZATION 阶段生效");
            }
            if (!"SOFT".equalsIgnoreCase(spec.type()) || spec.hard()) {
                continue;
            }
            problems.addAll(parameterProblems(code, spec.params()));
            Integer weight = integerWeight(spec.weight());
            if (weight == null) {
                problems.add("规则 " + code + " 的权重不是 0..100 的整数: " + spec.weight());
            }
        }
        for (String code : RuleCode.codes()) {
            if (!byCode.containsKey(code)) {
                problems.add("规则 " + code + " 缺失，引擎需要 8 条完整权重");
            }
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        if (problems.isEmpty()) {
            int total = 0;
            for (String code : RuleCode.codes()) {
                int weight = integerWeight(byCode.get(code).weight());
                weights.put(code, weight);
                total += weight;
            }
            if (total != RuleCode.TOTAL_WEIGHT) {
                problems.add("规则权重合计必须为 " + RuleCode.TOTAL_WEIGHT + "，实际为 " + total);
                weights.clear();
            }
        }
        return new Analysis(List.copyOf(problems), Map.copyOf(weights));
    }

    /**
     * 判断单条规则在给定参数下能否被引擎忠实执行。
     *
     * @param code 规则编码
     * @param params 规则参数
     * @return {@code true} 表示支持
     */
    public static boolean supports(String code, Map<String, Object> params) {
        return RuleCode.isSupported(code) && parameterProblems(code.trim(), params).isEmpty();
    }

    /**
     * 规则编码对应的引擎约束编码。
     *
     * <p>引擎约束名与 {@link RuleCode} 编码一致（见 {@code ConstraintCodes}）。</p>
     *
     * @param code 规则编码
     * @return 引擎约束编码；未知编码返回 {@code null}
     */
    public static String engineConstraint(String code) {
        return RuleCode.isSupported(code) ? code.trim() : null;
    }

    private static List<String> parameterProblems(String code, Map<String, Object> params) {
        Map<String, Object> values = params == null ? Map.of() : params;
        RuleCode rule = RuleCode.fromCode(code).orElse(null);
        if (rule == null) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (String key : values.keySet()) {
            if (!rule.getAllowedParams().contains(key)) {
                problems.add("规则 " + code + " 参数 " + key + " 不在白名单");
            }
        }
        switch (rule) {
            case MIN_TARDINESS, MIN_SETUP_TIME, MIN_MAKESPAN -> requireUnit(code, values, problems);
            case SAME_PRODUCT_CONTINUOUS -> {
                Object groupBy = values.get("groupBy");
                if (groupBy != null && !GROUP_BY_PRODUCT_FAMILY.equals(
                        String.valueOf(groupBy).trim().toUpperCase(Locale.ROOT))) {
                    problems.add("规则 " + code + " 的 groupBy=" + groupBy
                            + " 引擎不支持（仅按 productFamily 分组）");
                }
            }
            case BALANCE_LOAD -> {
                Object metric = values.get("metric");
                if (metric != null && !METRIC_LOAD_SECONDS.equals(
                        String.valueOf(metric).trim().toUpperCase(Locale.ROOT))) {
                    problems.add("规则 " + code + " 的 metric=" + metric
                            + " 引擎不支持（仅按加工秒数平方和计算负载）");
                }
            }
            case PRIORITY_RESPECT -> {
                if (values.containsKey("maxPriority")) {
                    problems.add("规则 " + code + " 的 maxPriority=" + values.get("maxPriority")
                            + " 引擎不支持（仅按优先级档位相对顺序惩罚，无阈值裁剪语义）");
                }
            }
            default -> {
                // EDD / SPT 没有白名单参数，未知参数已在上面按白名单拒绝。
            }
        }
        return problems;
    }

    private static void requireUnit(String code, Map<String, Object> values, List<String> problems) {
        Object unit = values.get("unit");
        if (unit != null && !UNIT_MINUTE.equals(
                String.valueOf(unit).trim().toUpperCase(Locale.ROOT))) {
            problems.add("规则 " + code + " 的 unit=" + unit
                    + " 引擎不支持（引擎固定按分钟计罚）");
        }
    }

    private static Integer integerWeight(BigDecimal weight) {
        if (weight == null) {
            return null;
        }
        try {
            BigDecimal stripped = weight.stripTrailingZeros();
            if (stripped.scale() > 0) {
                return null;
            }
            int value = stripped.intValueExact();
            return value >= RuleCode.MIN_WEIGHT && value <= RuleCode.MAX_WEIGHT ? value : null;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static BigDecimal toBigDecimal(Object value, int index) {
        if (value == null) {
            throw new IllegalArgumentException("compiled_spec[" + index + "] 缺少 weight");
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "compiled_spec[" + index + "] 的 weight 不是数值: " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toParams(Object value, int index) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("compiled_spec[" + index + "] 的 params 不是对象");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = Objects.toString(entry.getKey(), null);
            if (key == null) {
                continue;
            }
            params.put(key, entry.getValue());
        }
        return Map.copyOf(params);
    }

    private static String describe(String code) {
        return code == null || code.isEmpty() ? "<空>" : code;
    }
}
