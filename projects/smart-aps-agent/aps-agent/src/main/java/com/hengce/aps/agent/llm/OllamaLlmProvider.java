package com.hengce.aps.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 Ollama 原生 {@code /api/chat} 实现；真实 HTTP 调用，默认不启用。
 *
 * <p>显式传 {@code think=false}：qwen3 系列默认会把 token 消耗在思考通道，
 * 导致 {@code message.content} 为空（已在真实调用中确认）。</p>
 */
public final class OllamaLlmProvider implements LlmProvider {

    /**
     * provider 名。
     */
    public static final String NAME = "ollama";

    private final String baseUrl;

    private final String model;

    private final long timeoutMillis;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    /**
     * 构造 Ollama provider。
     *
     * @param baseUrl 基础地址，例如 {@code http://127.0.0.1:11434}
     * @param model 模型名，例如 {@code qwen3.5:9b}
     * @param timeoutMillis 超时毫秒
     * @param objectMapper JSON 序列化器
     */
    public OllamaLlmProvider(String baseUrl, String model, long timeoutMillis, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.timeoutMillis = timeoutMillis;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!available()) {
            throw new LlmCallException("provider configuration incomplete");
        }
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            if (request.maxTokens() != null) {
                options.put("num_predict", request.maxTokens());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
            payload.put("stream", false);
            payload.put("think", false);
            if (request.jsonOutput()) {
                payload.put("format", "json");
            }
            payload.put("options", options);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new LlmCallException("HTTP " + response.statusCode());
            }
            return parse(response.body(), System.currentTimeMillis() - started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("interrupted", false, exception);
        } catch (HttpTimeoutException exception) {
            throw new LlmCallException("request timed out", true, exception);
        } catch (JsonProcessingException exception) {
            throw new LlmCallException("invalid response payload", false, exception);
        } catch (IOException exception) {
            throw new LlmCallException("network error: " + exception.getClass().getSimpleName(),
                    false, true, exception);
        } catch (IllegalArgumentException exception) {
            throw new LlmCallException("invalid provider configuration", false, exception);
        }
    }

    private LlmResponse parse(String body, long latency) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode content = root.path("message").path("content");
        String text = content.isTextual() ? content.asText() : "";
        String usedModel = root.path("model").isTextual() ? root.path("model").asText() : model;
        Integer promptTokens = intOrNull(root.path("prompt_eval_count"));
        Integer completionTokens = intOrNull(root.path("eval_count"));
        return new LlmResponse(text, usedModel, promptTokens, completionTokens, latency);
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private String endpoint() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/api/chat";
    }
}
