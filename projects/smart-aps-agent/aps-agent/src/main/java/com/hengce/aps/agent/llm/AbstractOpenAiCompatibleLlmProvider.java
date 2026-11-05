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
 * OpenAI 兼容 {@code /chat/completions} 的公共实现，供 DeepSeek 与通义复用。
 *
 * <p>只从环境变量注入的配置读取地址与密钥；异常消息不包含完整 URL 或密钥，
 * 可直接写入日志。不跟随重定向，避免凭证被转发到未知地址。</p>
 */
public abstract class AbstractOpenAiCompatibleLlmProvider implements LlmProvider {

    private final String name;

    private final String baseUrl;

    private final String apiKey;

    private final String model;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private final long timeoutMillis;

    /**
     * 构造 provider。
     *
     * @param name provider 名
     * @param baseUrl 基础地址（可含 {@code /v1}，末尾斜杠会被去掉）
     * @param apiKey 密钥
     * @param model 模型名
     * @param timeoutMillis 超时毫秒
     * @param objectMapper JSON 序列化器
     */
    protected AbstractOpenAiCompatibleLlmProvider(String name, String baseUrl, String apiKey, String model,
                                                  long timeoutMillis, ObjectMapper objectMapper) {
        this.name = name;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
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
        return name;
    }

    @Override
    public boolean available() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!available()) {
            throw new LlmCallException("provider configuration incomplete");
        }
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", List.of(Map.of("role", "user", "content", request.prompt())));
            if (request.maxTokens() != null) {
                payload.put("max_tokens", request.maxTokens());
            }
            payload.put("stream", false);
            if (request.jsonOutput()) {
                payload.put("response_format", Map.of("type", "json_object"));
            }
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
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
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String text = content.isTextual() ? content.asText() : "";
        String usedModel = root.path("model").isTextual() ? root.path("model").asText() : model;
        Integer promptTokens = intOrNull(root.path("usage").path("prompt_tokens"));
        Integer completionTokens = intOrNull(root.path("usage").path("completion_tokens"));
        return new LlmResponse(text, usedModel, promptTokens, completionTokens, latency);
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private String endpoint() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/chat/completions";
    }
}
