package com.hengce.aps.service.schedule.infeasibility;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * C51 报告与修复候选的 JSON 编解码（落 {@code jsonb} 用）。
 *
 * <p>只用 jackson-databind 核心能力，显式注册 {@link OffsetDateTime} 序列化/反序列化，
 * 不依赖 jsr310 是否出现在各模块运行时类路径。时间统一写 ISO-8601 字符串。</p>
 */
final class InfeasibilityReportJson {

    /**
     * 编解码器。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new SimpleModule()
                    .addSerializer(OffsetDateTime.class, new JsonSerializer<OffsetDateTime>() {
                        @Override
                        public void serialize(OffsetDateTime value, JsonGenerator generator,
                                              SerializerProvider provider) throws IOException {
                            generator.writeString(value.toString());
                        }
                    })
                    .addDeserializer(OffsetDateTime.class, new JsonDeserializer<OffsetDateTime>() {
                        @Override
                        public OffsetDateTime deserialize(JsonParser parser,
                                                          DeserializationContext context)
                                throws IOException {
                            return OffsetDateTime.parse(parser.getText());
                        }
                    }));

    private InfeasibilityReportJson() {
    }

    /**
     * 报告序列化为 C51 完整 JSON。
     *
     * @param report 报告
     * @return JSON 文本
     */
    static String toJson(InfeasibilityReport report) {
        try {
            return MAPPER.writeValueAsString(report);
        } catch (IOException exception) {
            throw new IllegalStateException("C51 报告序列化失败", exception);
        }
    }

    /**
     * 从落库 JSON 还原报告。
     *
     * @param json 报告 JSON
     * @return 报告
     */
    static InfeasibilityReport fromJson(String json) {
        try {
            return MAPPER.readValue(json, InfeasibilityReport.class);
        } catch (IOException exception) {
            throw new IllegalStateException("C51 报告反序列化失败", exception);
        }
    }

    /**
     * 修复候选序列化为 JSON 数组。
     *
     * @param repairs 修复候选
     * @return JSON 文本
     */
    static String repairsToJson(List<RepairCandidate> repairs) {
        try {
            return MAPPER.writeValueAsString(repairs == null ? List.of() : repairs);
        } catch (IOException exception) {
            throw new IllegalStateException("修复候选序列化失败", exception);
        }
    }
}
