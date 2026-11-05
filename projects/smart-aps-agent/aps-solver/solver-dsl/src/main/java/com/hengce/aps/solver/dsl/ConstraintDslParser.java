package com.hengce.aps.solver.dsl;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 受控 DSL 解析器。
 *
 * <p>安全与边界：</p>
 * <ul>
 *   <li>使用 SnakeYAML {@link SafeConstructor}，禁止类加载、禁止任意对象标签；</li>
 *   <li>限制文档长度、嵌套深度与别名数量，杜绝 YAML bomb；</li>
 *   <li>只接受白名单顶层键，任何 {@code stream} 之类的键一律拒绝；</li>
 *   <li>规则项接受 C19 规范字段 {@code id/ruleCode/type/weight/enabled/params}，
 *       并兼容 {@code code}/{@code rule_code}；{@code type} 只允许 {@code SOFT}；</li>
 *   <li>规则编码必须命中 {@link RuleCode}，参数键必须命中规则声明的白名单；</li>
 *   <li>8 条规则权重之和必须等于 {@link RuleCode#TOTAL_WEIGHT}。</li>
 * </ul>
 *
 * <p>单数 {@code constraint:} 别名（M3-BE-06）：只保留“单对象”这一版语义，
 * 等价于 {@code constraints} 的单元素数组；<b>不再接受</b> {@code constraint:} 下写数组。
 * 别名仅作迁移期容错，<b>不承诺单条可激活</b>（激活要求恰好 8 条）。
 * 重复的同名 {@code constraint:} 键不受支持，由 {@code allowDuplicateKeys(false)}
 * 直接报 {@code 42201}。</p>
 */
public final class ConstraintDslParser implements ConstraintDslLoader {

    /**
     * DSL 文本最大字符数（256KB，C19 冻结规则 4）。
     */
    public static final int MAX_SOURCE_LENGTH = 256 * 1024;

    /**
     * YAML 最大嵌套深度。
     */
    public static final int MAX_NESTING_DEPTH = 32;

    private static final int MAX_ALIASES = 50;

    private static final Set<String> ROOT_KEYS = Set.of("version", "constraints", "constraint");

    private static final Set<String> ITEM_KEYS = Set.of(
            "id", "code", "ruleCode", "rule_code", "type", "weight", "enabled", "params");

    private static final Map<String, Set<String>> PARAM_VALUES = Map.of(
            "unit", Set.of("MINUTE", "SECOND", "HOUR"),
            "metric", Set.of("LOAD_SECONDS", "TASK_COUNT"),
            "groupBy", Set.of("PRODUCT_FAMILY", "MATERIAL"),
            "maxPriority", Set.of()
    );

    private final boolean legacySingularAlias;

    /**
     * 构造函数。
     *
     * @param legacySingularAlias 是否允许单数 {@code constraint:} 兼容别名
     */
    public ConstraintDslParser(boolean legacySingularAlias) {
        this.legacySingularAlias = legacySingularAlias;
    }

    /**
     * 默认启用单数别名（保留到 M2 结束）。
     */
    public ConstraintDslParser() {
        this(true);
    }

    @Override
    public ConstraintDslDocument load(String source) {
        Parsed parsed = parse(source);
        if (!parsed.report().valid()) {
            throw new DslValidationException(parsed.report());
        }
        return parsed.document();
    }

    /**
     * 只校验不抛异常，用于预检接口。
     *
     * @param source YAML 文本
     * @return 校验报告
     */
    public DslValidationReport validate(String source) {
        return parse(source).report();
    }

    private Parsed parse(String source) {
        List<DslValidationIssue> issues = new ArrayList<>();
        if (source == null || source.isBlank()) {
            issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "dsl", "DSL 文本为空"));
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            issues.add(new DslValidationIssue(DslErrorCodes.LIMIT_EXCEEDED, "dsl",
                    "DSL 文本长度 " + source.length() + " 超过上限 " + MAX_SOURCE_LENGTH));
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        Object root;
        try {
            root = yaml().load(new StringReader(source));
        } catch (YAMLException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("duplicate key")) {
                issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "dsl",
                        "YAML 存在重复键：重复的 constraint 键不受支持，请改用 constraints 数组"));
            } else {
                issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "dsl",
                        "YAML 解析失败: " + message));
            }
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "dsl", "DSL 根节点必须是映射"));
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        return parseRoot(rootMap, issues);
    }

    private Parsed parseRoot(Map<?, ?> rootMap, List<DslValidationIssue> issues) {
        for (Object key : rootMap.keySet()) {
            if (!(key instanceof String rootKey) || !ROOT_KEYS.contains(rootKey)) {
                issues.add(new DslValidationIssue(DslErrorCodes.UNKNOWN_RULE, "dsl",
                        "不支持的顶层键: " + key));
            }
        }
        if (issues.stream().anyMatch(issue -> DslErrorCodes.UNKNOWN_RULE.equals(issue.code()))) {
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        Object singular = rootMap.get("constraint");
        boolean hasArray = rootMap.containsKey("constraints");
        if (singular != null && hasArray) {
            issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "dsl",
                    "constraints 与 constraint 不能同时出现"));
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        if (singular != null && !legacySingularAlias) {
            issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "constraint",
                    "单数 constraint 别名已关闭，请使用 constraints 数组"));
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        int version = readVersion(rootMap.get("version"), issues);
        Object rawConstraints = hasArray ? rootMap.get("constraints") : singular;
        if (rawConstraints == null) {
            issues.add(new DslValidationIssue(DslErrorCodes.MISSING_RULE, "constraints", "缺少 constraints 数组"));
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        List<Object> items = new ArrayList<>();
        if (hasArray) {
            if (!(rawConstraints instanceof List<?> list)) {
                issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "constraints", "constraints 必须是数组"));
                return new Parsed(DslValidationReport.failed(issues), null);
            }
            items.addAll(list);
        } else if (rawConstraints instanceof Map<?, ?>) {
            items.add(rawConstraints);
        } else {
            issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "constraint",
                    "constraint 只支持单个映射（M3 起不再接受数组）"));
            return new Parsed(DslValidationReport.failed(issues), null);
        }

        List<ConstraintDslSpec> specs = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        for (int index = 0; index < items.size(); index++) {
            parseItem(items.get(index), "constraints[" + index + "]", seenCodes, specs, issues);
        }
        if (seenCodes.size() != RuleCode.CORE_RULE_COUNT) {
            List<String> missing = new ArrayList<>();
            for (RuleCode ruleCode : RuleCode.values()) {
                if (!seenCodes.contains(ruleCode.name())) {
                    missing.add(ruleCode.name());
                }
            }
            issues.add(new DslValidationIssue(DslErrorCodes.MISSING_RULE, "constraints",
                    "规则必须恰好 " + RuleCode.CORE_RULE_COUNT + " 条，缺失: " + String.join(",", missing)));
        }
        int total = specs.stream().mapToInt(ConstraintDslSpec::weight).sum();
        if (total != RuleCode.TOTAL_WEIGHT) {
            issues.add(new DslValidationIssue(DslErrorCodes.INVALID_WEIGHT, "constraints",
                    "8 条规则权重之和必须为 " + RuleCode.TOTAL_WEIGHT + "，实际为 " + total));
        }
        if (!issues.isEmpty()) {
            return new Parsed(DslValidationReport.failed(issues), null);
        }
        return new Parsed(DslValidationReport.ok(), new ConstraintDslDocument(version, specs));
    }

    private int readVersion(Object rawVersion, List<DslValidationIssue> issues) {
        if (rawVersion == null) {
            return 1;
        }
        if (rawVersion instanceof Integer integer && integer >= 1) {
            return integer;
        }
        if (rawVersion instanceof Long value && value >= 1 && value <= Integer.MAX_VALUE) {
            return value.intValue();
        }
        if (rawVersion instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.matches("\\d+(\\.\\d+)?")) {
                int major = Integer.parseInt(trimmed.split("\\.")[0]);
                if (major >= 1) {
                    return major;
                }
            }
        }
        issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, "version",
                "version 必须是正整数或 \"1.0\" 形式的版本字符串"));
        return 1;
    }

    private void parseItem(Object rawItem, String field, Set<String> seenCodes,
                           List<ConstraintDslSpec> specs, List<DslValidationIssue> issues) {
        if (!(rawItem instanceof Map<?, ?> item)) {
            issues.add(new DslValidationIssue(DslErrorCodes.SYNTAX_ERROR, field, "规则项必须是映射"));
            return;
        }
        for (Object key : item.keySet()) {
            if (!(key instanceof String itemKey) || !ITEM_KEYS.contains(itemKey)) {
                issues.add(new DslValidationIssue(DslErrorCodes.UNKNOWN_RULE, field,
                        "不支持的规则键: " + key));
                return;
            }
        }
        Object rawId = item.get("id");
        if (rawId != null && (!(rawId instanceof String id) || id.isBlank())) {
            issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + ".id",
                    "id 必须是非空字符串"));
            return;
        }
        Object rawType = item.get("type");
        if (rawType != null && !"SOFT".equals(rawType)) {
            issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + ".type",
                    "M2 的 DSL 仅支持 type=SOFT"));
            return;
        }
        boolean disabled = false;
        Object rawEnabled = item.get("enabled");
        if (rawEnabled != null) {
            if (!(rawEnabled instanceof Boolean enabled)) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + ".enabled",
                        "enabled 必须是布尔值"));
                return;
            }
            disabled = !enabled;
        }
        Object rawCode = item.containsKey("ruleCode") ? item.get("ruleCode")
                : item.containsKey("code") ? item.get("code") : item.get("rule_code");
        if (!(rawCode instanceof String code) || code.isBlank()) {
            issues.add(new DslValidationIssue(DslErrorCodes.MISSING_RULE, field + ".code", "缺少规则编码"));
            return;
        }
        RuleCode ruleCode = RuleCode.fromCode(code)
                .orElse(null);
        if (ruleCode == null) {
            issues.add(new DslValidationIssue(DslErrorCodes.UNKNOWN_RULE, field + ".code",
                    "未知规则编码: " + code));
            return;
        }
        if (!seenCodes.add(ruleCode.name())) {
            issues.add(new DslValidationIssue(DslErrorCodes.DUPLICATE_RULE, field + ".code",
                    "规则重复声明: " + ruleCode.name()));
            return;
        }
        int weight = disabled ? 0 : ruleCode.getDefaultWeight();
        Object rawWeight = item.get("weight");
        if (rawWeight != null) {
            Integer parsedWeight = integerOf(rawWeight);
            if (parsedWeight == null || parsedWeight < RuleCode.MIN_WEIGHT || parsedWeight > RuleCode.MAX_WEIGHT) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_WEIGHT, field + ".weight",
                        "权重必须是 " + RuleCode.MIN_WEIGHT + ".." + RuleCode.MAX_WEIGHT + " 的整数"));
                return;
            }
            if (disabled && parsedWeight > 0) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_WEIGHT, field + ".weight",
                        "enabled=false 时 weight 必须为 0"));
                return;
            }
            weight = parsedWeight;
        }
        Map<String, Object> params = parseParams(item.get("params"), field + ".params", ruleCode, issues);
        if (params == null) {
            return;
        }
        specs.add(new ConstraintDslSpec(ruleCode.name(), weight, params));
    }

    private Map<String, Object> parseParams(Object rawParams, String field, RuleCode ruleCode,
                                            List<DslValidationIssue> issues) {
        if (rawParams == null) {
            return Map.of();
        }
        if (!(rawParams instanceof Map<?, ?> paramMap)) {
            issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field, "params 必须是映射"));
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : paramMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field, "参数键必须是字符串"));
                return null;
            }
            if (!ruleCode.getAllowedParams().contains(key)) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + "." + key,
                        "规则 " + ruleCode.name() + " 不支持参数 " + key));
                return null;
            }
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + "." + key,
                        "参数值必须是标量"));
                return null;
            }
            Set<String> allowedValues = PARAM_VALUES.getOrDefault(key, Set.of());
            if (!allowedValues.isEmpty() && !allowedValues.contains(value.toString())) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + "." + key,
                        "参数值必须是 " + allowedValues));
                return null;
            }
            if ("maxPriority".equals(key) && integerOf(value) == null) {
                issues.add(new DslValidationIssue(DslErrorCodes.INVALID_PARAM, field + "." + key,
                        "maxPriority 必须是整数"));
                return null;
            }
            params.put(key, value);
        }
        return params;
    }

    private static Integer integerOf(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Long longValue && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        return null;
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(MAX_SOURCE_LENGTH);
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        return new Yaml(new SafeConstructor(options));
    }

    private record Parsed(DslValidationReport report, ConstraintDslDocument document) {
    }
}
