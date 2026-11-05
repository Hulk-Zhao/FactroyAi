package com.hengce.aps.agent.llm;

import com.hengce.aps.agent.config.ApsAgentProperties;
import com.hengce.aps.agent.testsupport.FakeLlmProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关门面单测：覆盖「未配置 / 显式关闭 / 缺配置 / 超时 / 错误 / 空输出 / 切换」，
 * 全部不依赖外网与真实凭证。
 */
class LlmGatewayTest {

    private static LlmGateway gateway(String providerName, LlmProvider... providers) {
        ApsAgentProperties properties = new ApsAgentProperties();
        properties.getLlm().setProvider(providerName);
        return new LlmGateway(new LlmProviderFactory(List.of(providers)), properties);
    }

    @Test
    void noneProviderIsNotConfiguredAndNeverThrows() {
        LlmGateway gateway = gateway(DisabledLlmProvider.NAME, new DisabledLlmProvider());

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertFalse(result.available());
        assertEquals(LlmDegradationReason.NO_PROVIDER_CONFIGURED, result.degradationReason());
        assertTrue(result.degradationDetail().contains("provider=none"));
        assertNull(result.output());
    }

    @Test
    void emptyProviderNameIsNotConfigured() {
        LlmGateway gateway = gateway("", new DisabledLlmProvider());

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.NO_PROVIDER_CONFIGURED, result.degradationReason());
    }

    @Test
    void unknownProviderIsNotConfigured() {
        LlmGateway gateway = gateway("typo-provider", FakeLlmProvider.replying("fake", "{}"));

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.NO_PROVIDER_CONFIGURED, result.degradationReason());
        assertTrue(result.degradationDetail().contains("typo-provider"));
    }

    @Test
    void providerWithoutCredentialsNamesMissingItems() {
        LlmGateway gateway = gateway("fake", FakeLlmProvider.unavailable("fake"));

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.NO_PROVIDER_CONFIGURED, result.degradationReason());
        assertTrue(result.degradationDetail().contains("APS_AGENT_LLM_BASE_URL"));
        assertTrue(result.degradationDetail().contains("APS_AGENT_LLM_MODEL"));
        assertTrue(result.degradationDetail().contains("APS_AGENT_LLM_API_KEY"));
    }

    @Test
    void networkFailureIsMappedToUnavailable() {
        LlmGateway gateway = gateway("fake", FakeLlmProvider.unreachable("fake"));

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.PROVIDER_UNAVAILABLE, result.degradationReason());
    }

    @Test
    void providerErrorIsDegradedWithDetail() {
        LlmGateway gateway = gateway("fake",
                FakeLlmProvider.failing("fake", false, "HTTP 401: unauthorized"));

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.PROVIDER_ERROR, result.degradationReason());
        assertEquals("HTTP 401: unauthorized", result.degradationDetail());
    }

    @Test
    void timeoutIsMappedToTimeoutReason() {
        LlmGateway gateway = gateway("fake", FakeLlmProvider.failing("fake", true, "request timed out"));

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.TIMEOUT, result.degradationReason());
    }

    @Test
    void blankResponseIsMappedToEmptyResponse() {
        LlmGateway gateway = gateway("fake", FakeLlmProvider.replying("fake", "  "));

        LlmResult result = gateway.call(LlmRequest.of("ping"));

        assertEquals(LlmDegradationReason.EMPTY_RESPONSE, result.degradationReason());
    }

    @Test
    void switchingProviderUsesSamePrompt() {
        FakeLlmProvider first = FakeLlmProvider.answering("provider-a", request -> "A:" + request.prompt());
        FakeLlmProvider second = FakeLlmProvider.answering("provider-b", request -> "B:" + request.prompt());
        LlmGateway gateway = gateway("provider-a", first, second);

        LlmResult viaFirst = gateway.call("provider-a", LlmRequest.of("same-prompt"));
        LlmResult viaSecond = gateway.call("provider-b", LlmRequest.of("same-prompt"));

        assertTrue(viaFirst.available());
        assertTrue(viaSecond.available());
        assertEquals("provider-a", viaFirst.provider());
        assertEquals("provider-b", viaSecond.provider());
        assertEquals("A:same-prompt", viaFirst.output());
        assertEquals("B:same-prompt", viaSecond.output());
        assertEquals(1, first.invocations());
        assertEquals(1, second.invocations());
    }
}
