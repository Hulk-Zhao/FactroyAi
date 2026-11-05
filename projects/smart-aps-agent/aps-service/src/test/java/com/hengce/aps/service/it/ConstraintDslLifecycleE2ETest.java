package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.service.strategy.ConstraintDslCreateCommand;
import com.hengce.aps.service.strategy.ConstraintDslService;
import com.hengce.aps.service.strategy.ConstraintDslView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

/**
 * M3-QA-01 约束 DSL 发布回滚 E2E：激活 → 激活新版本 → 回滚。
 *
 * <p>断言「同一 code 只有一个 ACTIVE」既由服务层保证，也由 V4 的部分唯一索引兜底：
 * 用例最后直接尝试把第二个版本改成 ACTIVE，必须被数据库拒绝。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
@TestPropertySource(properties = {"aps.solve.async-enabled=false"})
class ConstraintDslLifecycleE2ETest extends AbstractRealDbIntegrationTest {

    @Autowired
    private ConstraintDslService constraintDslService;

    private String code;

    @AfterEach
    void cleanupConstraintDslRows() {
        try {
            if (jdbc == null || code == null) {
                return;
            }
            jdbc.update("UPDATE constraint_dsl SET rollback_of = NULL WHERE code = ?", code);
            jdbc.update("DELETE FROM constraint_dsl WHERE code = ?", code);
        } finally {
            code = null;
        }
    }

    @Test
    @DisplayName("activate → activate 新版本 → rollback 后仅一个 ACTIVE")
    void activateNewVersionThenRollbackKeepsSingleActive() {
        code = tag("DSL-1");
        ConstraintDslView version1 = constraintDslService.create(
                new ConstraintDslCreateCommand(code, yaml(defaultWeights())));
        assertEquals(1, version1.versionNo(), "首个版本号应为 1");
        assertEquals("DRAFT", version1.status(), "新建版本应为 DRAFT");
        assertCompiled(version1);

        ConstraintDslView activated1 = constraintDslService.activate(version1.id());
        assertEquals("ACTIVE", activated1.status(), "首次激活后应为 ACTIVE");
        assertEquals(1L, count("SELECT count(*) FROM constraint_dsl WHERE code = ? "
                + "AND status = 'ACTIVE' AND deleted = false", code), "激活后应只有一个 ACTIVE");

        Map<String, Integer> secondWeights = defaultWeights();
        secondWeights.put("MIN_TARDINESS", 10);
        secondWeights.put("MIN_MAKESPAN", 20);
        ConstraintDslView version2 = constraintDslService.create(
                new ConstraintDslCreateCommand(code, yaml(secondWeights)));
        assertEquals(2, version2.versionNo(), "第二次创建应升版为 2");
        assertCompiled(version2);

        ConstraintDslView activated2 = constraintDslService.activate(version2.id());
        assertEquals("ACTIVE", activated2.status(), "新版本激活后应为 ACTIVE");
        assertEquals("ROLLBACK", constraintDslService.get(version1.id()).status(),
                "旧版本应被置为 ROLLBACK");
        assertEquals(1L, count("SELECT count(*) FROM constraint_dsl WHERE code = ? "
                + "AND status = 'ACTIVE' AND deleted = false", code), "升版后仍只能有一个 ACTIVE");

        ConstraintDslView rolledBack = constraintDslService.rollback(version1.id());
        assertEquals("ACTIVE", rolledBack.status(), "回滚目标应重新 ACTIVE");
        assertEquals("ROLLBACK", constraintDslService.get(version2.id()).status(),
                "回滚后原激活版本应置 ROLLBACK");
        assertEquals(version2.id(), rolledBack.rollbackOf(), "rollbackOf 应记录被回滚的源版本");
        assertCompiled(rolledBack);
        assertEquals(8, jsonArrayLength("SELECT jsonb_array_length(compiled_spec) FROM constraint_dsl "
                + "WHERE id = ?", rolledBack.id()), "库内 compiled_spec 必须是 8 条约束");
        assertEquals(1L, count("SELECT count(*) FROM constraint_dsl WHERE code = ? "
                + "AND status = 'ACTIVE' AND deleted = false", code), "回滚后仍只能有一个 ACTIVE");

        // 唯一索引兜底：绕过服务层直接写第二个 ACTIVE 必须被数据库拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE constraint_dsl SET status = 'ACTIVE' WHERE id = ?", version2.id()),
                "同一 code 的第二个 ACTIVE 必须被部分唯一索引拒绝");
        assertEquals(1L, count("SELECT count(*) FROM constraint_dsl WHERE code = ? "
                + "AND status = 'ACTIVE' AND deleted = false", code), "并发写入被拒后仍只有一个 ACTIVE");
    }

    private static void assertCompiled(ConstraintDslView view) {
        List<Map<String, Object>> spec = view.compiledSpec();
        assertNotNull(spec, "compiledSpec 不应为 null");
        assertFalse(spec.isEmpty(), "compiledSpec 不应为空");
        assertEquals(8, spec.size(), "compiledSpec 必须包含 8 条约束");
        for (Map<String, Object> item : spec) {
            assertNotNull(item.get("code"), "每条约束必须有 code");
            assertNotNull(item.get("weight"), "每条约束必须有 weight");
        }
        assertTrue(String.valueOf(view.yamlText()).contains("constraints"), "YAML 应被规范化为 constraints 数组");
    }

    private long jsonArrayLength(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertNotNull(value, "compiled_spec 数组长度应为确定值");
        return value;
    }

    private static Map<String, Integer> defaultWeights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("MIN_TARDINESS", 20);
        weights.put("EDD", 15);
        weights.put("SPT", 10);
        weights.put("SAME_PRODUCT_CONTINUOUS", 10);
        weights.put("MIN_SETUP_TIME", 10);
        weights.put("BALANCE_LOAD", 10);
        weights.put("PRIORITY_RESPECT", 15);
        weights.put("MIN_MAKESPAN", 10);
        return weights;
    }

    private static String yaml(Map<String, Integer> weights) {
        StringBuilder builder = new StringBuilder("version: 1\nconstraints:\n");
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            builder.append("  - code: ").append(entry.getKey()).append('\n')
                    .append("    weight: ").append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }
}
