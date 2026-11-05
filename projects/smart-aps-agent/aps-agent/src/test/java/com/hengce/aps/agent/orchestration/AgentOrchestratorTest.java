package com.hengce.aps.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.agent.checkpoint.AgentRunRecord;
import com.hengce.aps.agent.checkpoint.AgentRunStatus;
import com.hengce.aps.agent.config.ApsAgentProperties;
import com.hengce.aps.agent.llm.DisabledLlmProvider;
import com.hengce.aps.agent.llm.LlmDegradationReason;
import com.hengce.aps.agent.llm.LlmGateway;
import com.hengce.aps.agent.llm.LlmProvider;
import com.hengce.aps.agent.llm.LlmProviderFactory;
import com.hengce.aps.agent.prompt.PromptVersion;
import com.hengce.aps.agent.testsupport.FakeLlmProvider;
import com.hengce.aps.agent.testsupport.InMemoryCheckpointStore;
import com.hengce.aps.agent.testsupport.InMemoryPromptStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排链路单测：none 降级、假 provider 成功、步骤失败后从检查点续跑、输出校验降级。
 *
 * <p>使用 test-scope {@link FakeLlmProvider} 与内存存储，不依赖外网与数据库。</p>
 */
class AgentOrchestratorTest {

    private static final String DEFAULT_CONTENT = "上下文：{{context}}，请输出 JSON。";

    private static AgentOrchestrator orchestrator(LlmGateway gateway, InMemoryPromptStore promptStore,
                                                  InMemoryCheckpointStore checkpointStore,
                                                  ContextSource contextSource) {
        ObjectMapper mapper = new ObjectMapper();
        List<AgentStep> steps = List.of(
                new LoadContextStep(contextSource),
                new ComposePromptStep(promptStore, DEFAULT_CONTENT),
                new CallLlmStep(gateway, 64),
                new ValidateOutputStep(mapper));
        return new AgentOrchestrator(checkpointStore, steps, mapper, "agent.test", true);
    }

    private static LlmGateway gateway(String providerName, LlmProvider provider) {
        ApsAgentProperties properties = new ApsAgentProperties();
        properties.getLlm().setProvider(providerName);
        return new LlmGateway(new LlmProviderFactory(List.of(provider)), properties);
    }

    @Test
    void noneProviderDegradesButStillRunsAllSteps() {
        InMemoryPromptStore promptStore = new InMemoryPromptStore();
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        AgentOrchestrator orchestrator = orchestrator(
                gateway(DisabledLlmProvider.NAME, new DisabledLlmProvider()),
                promptStore, checkpointStore, state -> Map.of("context", "订单 A"));

        AgentRunResult result = orchestrator.run();

        assertEquals(AgentRunStatus.DEGRADED, result.status());
        assertEquals(LlmDegradationReason.NO_PROVIDER_CONFIGURED, result.degradationReason());
        assertNull(result.output());
        assertEquals(4, result.lastCompletedStep());
        assertEquals(4, checkpointStore.checkpointCount(result.runId()));
        AgentRunRecord run = checkpointStore.findRun(result.runId()).orElseThrow();
        assertEquals(AgentRunStatus.DEGRADED, run.status());
        assertEquals(LlmDegradationReason.NO_PROVIDER_CONFIGURED, run.degradationReason());
        assertEquals(4, run.lastCompletedStep());
    }

    @Test
    void fakeProviderCompletesAndPersistsEveryCheckpoint() {
        InMemoryPromptStore promptStore = new InMemoryPromptStore();
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        AgentOrchestrator orchestrator = orchestrator(
                gateway("fake", FakeLlmProvider.replying("fake", "{\"answer\":\"ok\"}")),
                promptStore, checkpointStore, state -> Map.of("context", "订单 A"));

        AgentRunResult result = orchestrator.run();

        assertEquals(AgentRunStatus.COMPLETED, result.status());
        assertFalse(result.degraded());
        assertEquals("{\"answer\":\"ok\"}", result.output());
        assertEquals("fake", result.provider());
        assertEquals(1, result.promptVersion());
        assertEquals(4, checkpointStore.checkpointCount(result.runId()));
        List<Integer> stepNumbers = checkpointStore.checkpoints(result.runId()).stream()
                .map(item -> item.stepNo()).toList();
        assertEquals(List.of(1, 2, 3, 4), stepNumbers);
        AgentRunRecord run = checkpointStore.findRun(result.runId()).orElseThrow();
        assertEquals(AgentRunStatus.COMPLETED, run.status());
        assertEquals(4, run.lastCompletedStep());
        assertNull(run.degradationReason());
    }

    @Test
    void resumeContinuesFromLastCheckpointAfterStepFailure() {
        InMemoryPromptStore promptStore = new InMemoryPromptStore();
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        AtomicInteger loads = new AtomicInteger();
        ContextSource contextSource = state -> {
            loads.incrementAndGet();
            return Map.of("context", "订单 B");
        };
        AgentOrchestrator orchestrator = orchestrator(
                gateway("fake", FakeLlmProvider.replying("fake", "{\"answer\":\"ok\"}")),
                promptStore, checkpointStore, contextSource);

        // 第 2 步（组装 Prompt）首次失败：第 1 步检查点已落库
        promptStore.failNextEnsure(new IllegalStateException("prompt store unavailable"));
        AgentRunResult first = orchestrator.run();

        assertEquals(AgentRunStatus.FAILED, first.status());
        assertEquals(1, first.lastCompletedStep());
        assertEquals(1, checkpointStore.checkpointCount(first.runId()));
        assertEquals(1, loads.get());

        AgentRunResult resumed = orchestrator.resume(first.runId());

        assertEquals(AgentRunStatus.COMPLETED, resumed.status());
        assertEquals(4, resumed.lastCompletedStep());
        assertEquals(4, checkpointStore.checkpointCount(first.runId()));
        assertEquals(1, loads.get(), "已完成的取数步骤不得重复执行");
        assertEquals(AgentRunStatus.COMPLETED,
                checkpointStore.findRun(first.runId()).orElseThrow().status());
    }

    @Test
    void invalidJsonOutputDegradesAndKeepsRawText() {
        InMemoryPromptStore promptStore = new InMemoryPromptStore();
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();
        AgentOrchestrator orchestrator = orchestrator(
                gateway("fake", FakeLlmProvider.replying("fake", "这不是 JSON")),
                promptStore, checkpointStore, state -> Map.of("context", "订单 C"));

        AgentRunResult result = orchestrator.run();

        assertEquals(AgentRunStatus.DEGRADED, result.status());
        assertEquals(LlmDegradationReason.INVALID_OUTPUT, result.degradationReason());
        assertEquals("这不是 JSON", result.output(), "结构化校验失败时必须保留原始文本");
    }

    @Test
    void composePromptSubstitutesVariablesAndSeedsVersionOne() {
        InMemoryPromptStore promptStore = new InMemoryPromptStore();
        ComposePromptStep step = new ComposePromptStep(promptStore, DEFAULT_CONTENT);
        AgentRunState state = new AgentRunState();
        state.setPromptKey("agent.test");
        state.setVariables(Map.of("context", "订单 D"));

        step.execute(state);

        assertEquals("上下文：订单 D，请输出 JSON。", state.getComposedPrompt());
        assertEquals(1, state.getPromptVersion());
        PromptVersion active = promptStore.findActive("agent.test").orElseThrow();
        assertEquals(1, active.version());
        assertTrue(active.active());
    }
}
