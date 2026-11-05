package com.hengce.aps.solver.dsl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL 解析与校验测试：白名单、权重合计、参数白名单、safe load、单数别名与数组序列化。
 */
class ConstraintDslParserTest {

    @Test
    void acceptsEightRulesWithWeightsSummingToHundred() {
        ConstraintDslDocument document = new ConstraintDslParser().load(compliantYaml());

        assertEquals(8, document.constraints().size());
        assertEquals(100, document.constraints().stream().mapToInt(ConstraintDslSpec::weight).sum());
        assertEquals(RuleCode.codes(), document.constraints().stream().map(ConstraintDslSpec::ruleCode).toList());
    }

    @Test
    void serializeAlwaysUsesConstraintsArray() {
        ConstraintDslDocument document = new ConstraintDslParser().load(compliantYaml());
        String canonical = new ConstraintDslSerializer().serialize(document);

        assertTrue(canonical.contains("constraints:"), canonical);
        assertFalse(canonical.contains("\nconstraint:"), canonical);
        ConstraintDslDocument reparsed = new ConstraintDslParser(false).load(canonical);
        assertEquals(document.constraints(), reparsed.constraints());
    }

    @Test
    void legacySingularAliasIsConvertedButSinglyDeclaredDocumentFailsRuleCount() {
        String singular = "constraint:\n  code: MIN_TARDINESS\n  weight: 20\n";
        DslValidationReport report = new ConstraintDslParser(true).validate(singular);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.MISSING_RULE, report.primaryCode(),
                "单数别名应被接受并转为单元素数组，随后因规则数不足而报 42207");
    }

    @Test
    void rejectsSingularConstraintArrayAfterAliasCleanup() {
        String yaml = "version: 1\nconstraint:\n"
                + "  - code: MIN_TARDINESS\n    weight: 20\n"
                + "  - code: EDD\n    weight: 15\n";
        DslValidationReport report = new ConstraintDslParser(true).validate(yaml);

        assertFalse(report.valid(), "M3-BE-06 只保留单对象一版，数组形态不再接受");
        assertEquals(DslErrorCodes.SYNTAX_ERROR, report.primaryCode());
        assertTrue(report.issues().get(0).message().contains("单个映射"),
                report.issues().get(0).message());
    }

    @Test
    void rejectsRepeatedSingularConstraintKeys() {
        String yaml = "version: 1\n"
                + "constraint:\n  ruleCode: MIN_TARDINESS\n  weight: 20\n"
                + "constraint:\n  ruleCode: EDD\n  weight: 15\n";
        DslValidationReport report = new ConstraintDslParser(true).validate(yaml);

        assertFalse(report.valid(), "重复同名键不在别名覆盖范围内（结论 B）");
        assertEquals(DslErrorCodes.SYNTAX_ERROR, report.primaryCode());
        assertTrue(report.issues().get(0).message().contains("重复"),
                report.issues().get(0).message());
    }

    @Test
    void acceptsContractFrozenItemShape() {
        ConstraintDslDocument document = new ConstraintDslParser().load(contractShapeYaml());

        assertEquals(8, document.constraints().size());
        assertEquals(1, document.version(), "C19 样例会写 version: \"1.0\"，应归一化为文档版本 1");
        assertEquals(100, document.constraints().stream().mapToInt(ConstraintDslSpec::weight).sum());
        assertEquals(RuleCode.codes(), document.constraints().stream().map(ConstraintDslSpec::ruleCode).toList());
    }

    @Test
    void serializerEmitsContractFieldNames() {
        ConstraintDslDocument document = new ConstraintDslParser().load(compliantYaml());
        String canonical = new ConstraintDslSerializer().serialize(document);

        assertTrue(canonical.contains("ruleCode: MIN_TARDINESS"), canonical);
        assertTrue(canonical.contains("type: SOFT"), canonical);
        assertTrue(canonical.contains("id: min_tardiness"), canonical);
        assertFalse(canonical.contains("\n  - code:"), canonical);
    }

    @Test
    void legacySingularAliasCanBeDisabled() {
        String singular = "constraint:\n  code: MIN_TARDINESS\n  weight: 20\n";
        DslValidationReport report = new ConstraintDslParser(false).validate(singular);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.SYNTAX_ERROR, report.primaryCode());
    }

    @Test
    void rejectsUnknownRuleCode() {
        String yaml = compliantYaml().replace("code: EDD", "code: NOT_A_RULE");
        DslValidationReport report = new ConstraintDslParser().validate(yaml);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.UNKNOWN_RULE, report.primaryCode());
    }

    @Test
    void rejectsDuplicateRule() {
        String yaml = compliantYaml().replace("code: EDD", "code: MIN_TARDINESS");
        DslValidationReport report = new ConstraintDslParser().validate(yaml);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.DUPLICATE_RULE, report.primaryCode());
    }

    @Test
    void rejectsWeightSumOtherThanHundred() {
        String yaml = compliantYaml().replace("  weight: 15\n", "  weight: 16\n");
        DslValidationReport report = new ConstraintDslParser().validate(yaml);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.INVALID_WEIGHT, report.primaryCode());
    }

    @Test
    void rejectsWeightOutOfRange() {
        String yaml = "version: 1\nconstraints:\n  - code: MIN_TARDINESS\n    weight: 101\n";
        DslValidationReport report = new ConstraintDslParser().validate(yaml);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.INVALID_WEIGHT, report.primaryCode());
    }

    @Test
    void rejectsUnknownParamKey() {
        String yaml = compliantYaml().replace("    weight: 20\n",
                "    weight: 20\n    params:\n      unsafeStream: true\n");
        DslValidationReport report = new ConstraintDslParser().validate(yaml);

        assertFalse(report.valid());
        assertEquals(DslErrorCodes.INVALID_PARAM, report.primaryCode());
    }

    @Test
    void acceptsWhitelistedParams() {
        ConstraintDslDocument document = new ConstraintDslParser().load(yamlWithParams());

        assertTrue(document.constraints().stream().anyMatch(spec -> spec.params().containsKey("unit")));
        assertTrue(document.constraints().stream().anyMatch(spec -> spec.params().containsKey("groupBy")));
        assertTrue(document.constraints().stream().anyMatch(spec -> spec.params().containsKey("metric")));
        assertTrue(document.constraints().stream().anyMatch(spec -> spec.params().containsKey("maxPriority")));
    }

    @Test
    void rejectsArbitraryYamlTagsInsteadOfLoadingClasses() {
        String yaml = "version: 1\nconstraints: !!python/object:java.lang.ProcessBuilder\n  command: calc\n";

        DslValidationReport report = new ConstraintDslParser().validate(yaml);
        assertFalse(report.valid());
        assertEquals(DslErrorCodes.SYNTAX_ERROR, report.primaryCode());
    }

    @Test
    void rejectsUnknownTopLevelKeySuchAsStream() {
        String yaml = compliantYaml() + "stream:\n  - filter: system\n";

        DslValidationReport report = new ConstraintDslParser().validate(yaml);
        assertFalse(report.valid());
        assertEquals(DslErrorCodes.UNKNOWN_RULE, report.primaryCode());
    }

    @Test
    void rejectsOversizeDocument() {
        String yaml = "version: 1\n" + "x".repeat(ConstraintDslParser.MAX_SOURCE_LENGTH);

        DslValidationReport report = new ConstraintDslParser().validate(yaml);
        assertFalse(report.valid());
        assertEquals(DslErrorCodes.LIMIT_EXCEEDED, report.primaryCode());
    }

    @Test
    void throwsValidationExceptionWithReportOnLoad() {
        DslValidationException exception = assertThrows(DslValidationException.class,
                () -> new ConstraintDslParser().load("version: 1\n"));

        assertFalse(exception.getReport().valid());
        assertEquals(DslErrorCodes.MISSING_RULE, exception.getErrorCode());
    }

    @Test
    void compilesToSoftConstraintSpecs() {
        ConstraintDslDocument document = new ConstraintDslParser().load(compliantYaml());
        var specs = new ConstraintDslCompiler().compile(document);

        assertEquals(8, specs.size());
        assertTrue(specs.stream().allMatch(spec -> "SOFT".equals(spec.type())));
        assertTrue(specs.stream().noneMatch(spec -> spec.hard()));
        assertEquals(8, specs.stream().filter(spec -> "OPTIMIZATION".equals(spec.phase())).count());
    }

    /**
     * 生成一份合规 DSL：8 条规则、默认权重、合计 100。
     *
     * @return YAML 文本
     */
    static String compliantYaml() {
        Map<String, Integer> weights = new LinkedHashMap<>(RuleCode.defaultWeights());
        StringBuilder builder = new StringBuilder("version: 1\nconstraints:\n");
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            builder.append("  - code: ").append(entry.getKey()).append('\n')
                    .append("    weight: ").append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 生成 C19 冻结样例形状（id/ruleCode/type/weight/enabled/params）的合规 DSL。
     *
     * @return YAML 文本
     */
    static String contractShapeYaml() {
        StringBuilder builder = new StringBuilder("version: \"1.0\"\nconstraints:\n");
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            builder.append("  - id: ").append(entry.getKey().toLowerCase(java.util.Locale.ROOT)).append('\n')
                    .append("    ruleCode: ").append(entry.getKey()).append('\n')
                    .append("    type: SOFT\n")
                    .append("    weight: ").append(entry.getValue()).append('\n')
                    .append("    enabled: true\n")
                    .append("    params: {}\n");
        }
        return builder.toString();
    }

    /**
     * 生成一份带白名单参数的合规 DSL。
     *
     * @return YAML 文本
     */
    static String yamlWithParams() {
        Map<String, String> paramBlocks = Map.of(
                "MIN_TARDINESS", "    params:\n      unit: HOUR\n",
                "SAME_PRODUCT_CONTINUOUS", "    params:\n      groupBy: MATERIAL\n",
                "BALANCE_LOAD", "    params:\n      metric: TASK_COUNT\n",
                "PRIORITY_RESPECT", "    params:\n      maxPriority: 5\n",
                "MIN_MAKESPAN", "    params:\n      unit: MINUTE\n");
        StringBuilder builder = new StringBuilder("version: 1\nconstraints:\n");
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            builder.append("  - code: ").append(entry.getKey()).append('\n')
                    .append("    weight: ").append(entry.getValue()).append('\n')
                    .append(paramBlocks.getOrDefault(entry.getKey(), ""));
        }
        return builder.toString();
    }
}
