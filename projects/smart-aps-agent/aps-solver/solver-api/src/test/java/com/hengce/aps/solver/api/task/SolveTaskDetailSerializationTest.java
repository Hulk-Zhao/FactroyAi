package com.hengce.aps.solver.api.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * C20 §24.2：全局 {@code non_null} 不得作用于 {@code GET /solve-tasks/{id}} 的 metrics 内容。
 *
 * <p>{@code metrics} 直接来自 JSONB，允许含 {@code null} 值（如引擎未产出最佳分时的
 * {@code "bestScore": null}）。若被全局 {@code non_null} 抑制，{@code "查过、确实没有"} 会被读成
 * “没算”，因此这里用与生产同口径的 {@code NON_NULL} 默认值验证 Map 内 null 仍然输出。</p>
 */
class SolveTaskDetailSerializationTest {

    @Test
    void metricsNullValuesMustBeSerializedEvenWithGlobalNonNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("phase", "FAILED");
        metrics.put("engine", "ORTOOLS");
        metrics.put("engineFallback", true);
        metrics.put("engineFallbackDetail", "ENGINE_ERROR");
        metrics.put("bestScore", null);

        SolveTaskDetail detail = new SolveTaskDetail(7L, UUID.randomUUID(), null, "ORTOOLS",
                "FAILED", 1.0, "FAILED", null, null, null, 0L, null, "FAILED",
                "引擎异常", true, metrics, null, null, null, null, List.of());

        String json = mapper.writeValueAsString(detail);

        assertTrue(json.contains("\"bestScore\":null"),
                "metrics 内的 null 值必须输出，不能被全局 non_null 抑制: " + json);
        // 对照：顶层声明的可空字段同样按 null 输出（类级 @JsonInclude(ALWAYS) 生效）。
        assertTrue(json.contains("\"strategyId\":null"), "顶层可空字段必须输出 null: " + json);
        assertTrue(json.contains("\"errorCode\":\"FAILED\""), json);
        assertFalse(json.contains("\"metrics\":{}"), "metrics 不得被整体省略: " + json);
    }
}
