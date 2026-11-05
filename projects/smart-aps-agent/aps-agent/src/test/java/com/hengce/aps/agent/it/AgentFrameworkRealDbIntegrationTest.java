package com.hengce.aps.agent.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.agent.checkpoint.AgentRunRecord;
import com.hengce.aps.agent.checkpoint.AgentRunStatus;
import com.hengce.aps.agent.checkpoint.CheckpointStore;
import com.hengce.aps.agent.orchestration.AgentRunResult;
import com.hengce.aps.agent.orchestration.AgentOrchestrator;
import com.hengce.aps.agent.orchestration.AgentRunState;
import com.hengce.aps.agent.prompt.PromptStore;
import com.hengce.aps.agent.prompt.PromptVersion;
import com.hengce.aps.common.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V13 真库集成测试：验证 Prompt 版本持久化/回滚、检查点入库、运行摘要与断点续跑。
 *
 * <p>仅在 {@code APS_TEST_DB_ENABLED=true} 且能连上自建测试库时执行；
 * 数据全部带 {@code AGENT-TEST-} 前缀并在用例后清理，结论端点/接口零改动。
 * 测试使用 test-scope 假 provider，不访问任何模型。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "true")
@SpringBootTest(classes = AgentDbTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "aps.agent.llm.provider=fake")
class AgentFrameworkRealDbIntegrationTest {

    private static final String PREFIX = "AGENT-TEST-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PromptStore promptStore;

    @Autowired
    private CheckpointStore checkpointStore;

    @Autowired
    private AgentOrchestrator orchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_run WHERE prompt_key LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM agent_prompt_version WHERE prompt_key LIKE ?", PREFIX + "%");
    }

    @Test
    void v13TablesAreCreatedByFlyway() {
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT to_regclass('agent_prompt_version') IS NOT NULL", Boolean.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT to_regclass('agent_run') IS NOT NULL", Boolean.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT to_regclass('agent_checkpoint') IS NOT NULL", Boolean.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '13'", Integer.class));
    }

    @Test
    void promptVersionsPersistAcrossInstancesAndRollBack() {
        String key = newKey();
        PromptVersion first = promptStore.register(key, "模板 A", "初始", null);
        PromptVersion second = promptStore.register(key, "模板 B", "第二版", null);

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        promptStore.activate(key, second.version());
        assertEquals(2, promptStore.findActive(key).orElseThrow().version());

        PromptVersion rolledBack = promptStore.rollback(key).orElseThrow();
        assertEquals(1, rolledBack.version(), "回滚应回到上一版");
        assertEquals(1, promptStore.findActive(key).orElseThrow().version());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_prompt_version WHERE prompt_key = ? AND active", Integer.class, key));
        assertEquals(2, promptStore.list(key).size(), "历史版本必须保留");
        assertTrue(promptStore.rollback(key).isEmpty(), "已是最早版本时回滚应为空");
    }

    @Test
    void orchestrationPersistsRunAndCheckpoints() {
        String key = newKey();
        AgentRunResult result = orchestrator.run(key);

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertEquals(4, checkpointStore.checkpoints(result.runId()).size());

        Map<String, Object> runRow = jdbcTemplate.queryForMap(
                "SELECT status, provider, prompt_version, last_completed_step FROM agent_run WHERE id = ?",
                result.runId());
        assertEquals("COMPLETED", runRow.get("status"));
        assertEquals("fake", runRow.get("provider"));
        assertEquals(1, ((Number) runRow.get("prompt_version")).intValue());
        assertEquals(4, ((Number) runRow.get("last_completed_step")).intValue());

        Map<String, Object> checkpointRow = jdbcTemplate.queryForMap(
                "SELECT step_name, state FROM agent_checkpoint WHERE run_id = ? AND step_no = 4",
                result.runId());
        assertEquals("validate-output", checkpointRow.get("step_name"));
        assertNotNull(checkpointRow.get("state"));
        assertTrue(String.valueOf(checkpointRow.get("state")).contains("\"lastCompletedStep\""));
    }

    @Test
    void resumeFromPersistedCheckpointCompletesRemainingSteps() throws Exception {
        String key = newKey();
        UUID tenantId = TenantContext.getTenantId();
        AgentRunRecord run = checkpointStore.createRun(tenantId, key, "{}");

        AgentRunState state = new AgentRunState();
        state.setRunId(run.id());
        state.setPromptKey(key);
        state.setVariables(Map.of("context", "真库续跑上下文"));
        state.setLastCompletedStep(1);
        state.setLastStepName("load-context");
        checkpointStore.saveCheckpoint(run.id(), tenantId, 1, "load-context",
                objectMapper.writeValueAsString(state));
        checkpointStore.updateRun(run.id(), AgentRunStatus.FAILED, null, null, null, 1, "simulated crash");

        AgentRunResult resumed = orchestrator.resume(run.id());

        assertEquals(AgentRunStatus.COMPLETED, resumed.status());
        assertEquals(4, resumed.lastCompletedStep());
        assertEquals(4, checkpointStore.checkpoints(run.id()).size());
        assertEquals(AgentRunStatus.COMPLETED, checkpointStore.findRun(run.id()).orElseThrow().status());
    }

    private static String newKey() {
        return PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }
}
