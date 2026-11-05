package com.hengce.aps.solver.dsl;

import com.hengce.aps.solver.api.ConstraintSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL 到求解引擎的映射测试：默认参数全支持，引擎无对应语义的参数必须被点名拒绝。
 */
class ConstraintDslEngineMappingTest {

    @Test
    void acceptsDefaultEightRulesAndProducesWeights() {
        ConstraintDslEngineMapping.Analysis analysis =
                ConstraintDslEngineMapping.analyze(defaultSpecs(Map.of()));

        assertTrue(analysis.supported());
        assertEquals(RuleCode.defaultWeights(), analysis.weights());
        assertEquals("MIN_SETUP_TIME", ConstraintDslEngineMapping.engineConstraint("MIN_SETUP_TIME"));
        assertEquals(null, ConstraintDslEngineMapping.engineConstraint("UNKNOWN"));
    }

    @Test
    void rejectsNonMinuteUnitsNamingEachRule() {
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        params.put("MIN_TARDINESS", Map.of("unit", "HOUR"));
        params.put("MIN_SETUP_TIME", Map.of("unit", "SECOND"));
        params.put("MIN_MAKESPAN", Map.of("unit", "HOUR"));
        ConstraintDslEngineMapping.Analysis analysis =
                ConstraintDslEngineMapping.analyze(defaultSpecs(params));

        assertFalse(analysis.supported());
        assertTrue(analysis.problems().stream().anyMatch(p -> p.contains("MIN_TARDINESS")));
        assertTrue(analysis.problems().stream().anyMatch(p -> p.contains("MIN_SETUP_TIME")));
        assertTrue(analysis.problems().stream().anyMatch(p -> p.contains("MIN_MAKESPAN")));
    }

    @Test
    void rejectsGroupByMetricAndMaxPriorityThatEngineCannotHonor() {
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        params.put("SAME_PRODUCT_CONTINUOUS", Map.of("groupBy", "MATERIAL"));
        params.put("BALANCE_LOAD", Map.of("metric", "TASK_COUNT"));
        params.put("PRIORITY_RESPECT", Map.of("maxPriority", "5"));
        ConstraintDslEngineMapping.Analysis analysis =
                ConstraintDslEngineMapping.analyze(defaultSpecs(params));

        assertFalse(analysis.supported());
        assertTrue(analysis.problems().stream().anyMatch(p -> p.contains("SAME_PRODUCT_CONTINUOUS")));
        assertTrue(analysis.problems().stream().anyMatch(p -> p.contains("BALANCE_LOAD")));
        assertTrue(analysis.problems().stream().anyMatch(p -> p.contains("PRIORITY_RESPECT")));
    }

    @Test
    void rejectsMissingRuleNonIntegerWeightAndWrongTotal() {
        List<ConstraintSpec> specs = new ArrayList<>(defaultSpecs(Map.of()));
        specs.removeIf(spec -> spec.code().equals("SPT"));
        ConstraintDslEngineMapping.Analysis missing =
                ConstraintDslEngineMapping.analyze(specs);
        assertTrue(missing.problems().stream().anyMatch(p -> p.contains("SPT")));

        List<ConstraintSpec> decimal = new ArrayList<>(defaultSpecs(Map.of()));
        decimal.set(0, new ConstraintSpec("MIN_TARDINESS", "SOFT",
                new BigDecimal("20.5"), false, "OPTIMIZATION", Map.of()));
        ConstraintDslEngineMapping.Analysis nonInteger =
                ConstraintDslEngineMapping.analyze(decimal);
        assertTrue(nonInteger.problems().stream().anyMatch(p -> p.contains("整数")));

        List<ConstraintSpec> wrongTotal = new ArrayList<>(defaultSpecs(Map.of()));
        wrongTotal.set(0, new ConstraintSpec("MIN_TARDINESS", "SOFT",
                BigDecimal.valueOf(10), false, "OPTIMIZATION", Map.of()));
        ConstraintDslEngineMapping.Analysis total =
                ConstraintDslEngineMapping.analyze(wrongTotal);
        assertTrue(total.problems().stream().anyMatch(p -> p.contains("合计")));
    }

    @Test
    void restoresCompiledSpecFromJsonbShape() {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", entry.getKey());
            item.put("type", "SOFT");
            item.put("weight", entry.getValue());
            item.put("hard", false);
            item.put("phase", "OPTIMIZATION");
            item.put("params", Map.of());
            raw.add(item);
        }

        List<ConstraintSpec> specs = ConstraintDslEngineMapping.fromCompiledSpec(raw);

        assertEquals(8, specs.size());
        assertEquals(new BigDecimal("20"), specs.get(0).weight());
        assertThrows(IllegalArgumentException.class,
                () -> ConstraintDslEngineMapping.fromCompiledSpec(List.of()));
    }

    private static List<ConstraintSpec> defaultSpecs(Map<String, Map<String, Object>> paramsByRule) {
        List<ConstraintSpec> specs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            specs.add(new ConstraintSpec(entry.getKey(), "SOFT",
                    BigDecimal.valueOf(entry.getValue()), false, "OPTIMIZATION",
                    paramsByRule.getOrDefault(entry.getKey(), Map.of())));
        }
        return specs;
    }
}
