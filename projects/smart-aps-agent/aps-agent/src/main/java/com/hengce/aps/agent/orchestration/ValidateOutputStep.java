package com.hengce.aps.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.agent.llm.LlmDegradationReason;

/**
 * 步骤 4：结构化输出校验（必须是 JSON 对象，允许 {@code ```json} 围栏）。
 *
 * <p>校验失败不抛异常，而是置为降级 {@link LlmDegradationReason#INVALID_OUTPUT}，
 * 并保留原始输出文本，供上层在「保留原文」路径使用。</p>
 */
public final class ValidateOutputStep implements AgentStep {

    private static final int DETAIL_LIMIT = 160;

    private final ObjectMapper objectMapper;

    /**
     * 构造步骤。
     *
     * @param objectMapper JSON 解析器
     */
    public ValidateOutputStep(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int number() {
        return 4;
    }

    @Override
    public String name() {
        return "validate-output";
    }

    @Override
    public void execute(AgentRunState state) {
        if (state.isDegraded()) {
            state.setOutputValid(false);
            state.setValidationError("skipped: llm degraded (" + state.getDegradationReason() + ")");
            return;
        }
        String text = state.getOutput();
        if (text == null || text.isBlank()) {
            state.setOutputValid(false);
            state.setValidationError("empty output");
            markInvalid(state, "empty output");
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(stripFence(text));
            if (node != null && node.isObject()) {
                state.setOutputValid(true);
                state.setValidationError(null);
                return;
            }
            markInvalid(state, "output is not a json object");
        } catch (JsonProcessingException exception) {
            markInvalid(state, "output is not valid json: " + exception.getOriginalMessage());
        }
    }

    private static void markInvalid(AgentRunState state, String detail) {
        String flattened = detail.replaceAll("[\\r\\n\\t]", " ").trim();
        String trimmed = flattened.length() <= DETAIL_LIMIT ? flattened : flattened.substring(0, DETAIL_LIMIT);
        state.setOutputValid(false);
        state.setValidationError(trimmed);
        state.setDegraded(true);
        state.setDegradationReason(LlmDegradationReason.INVALID_OUTPUT);
        state.setDegradationDetail(trimmed);
    }

    private static String stripFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed;
        }
        int closing = trimmed.lastIndexOf("```");
        if (closing <= firstLineEnd) {
            return trimmed.substring(firstLineEnd + 1).trim();
        }
        return trimmed.substring(firstLineEnd + 1, closing).trim();
    }
}
