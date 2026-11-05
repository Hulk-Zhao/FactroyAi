package com.hengce.aps.web.filter;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * traceId 过滤器测试：生成、透传、响应头与上下文清理。
 */
class TraceIdFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PingController())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        MDC.clear();
    }

    @Test
    void shouldGenerateTraceIdWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceContext.HEADER_NAME))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    void shouldPropagateValidInboundTraceId() throws Exception {
        mockMvc.perform(get("/ping").header(TraceContext.HEADER_NAME, "client-trace-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.HEADER_NAME, "client-trace-0001"))
                .andExpect(jsonPath("$.traceId").value("client-trace-0001"));
    }

    @Test
    void shouldReplaceIllegalInboundTraceId() throws Exception {
        mockMvc.perform(get("/ping").header(TraceContext.HEADER_NAME, "bad trace id!"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.HEADER_NAME, org.hamcrest.Matchers.not("bad trace id!")));
    }

    @Test
    void shouldClearContextAfterRequest() throws Exception {
        mockMvc.perform(get("/ping")).andExpect(status().isOk());

        assertEquals("", TraceContext.getTraceId());
        assertNull(MDC.get(TraceContext.MDC_KEY));
        assertFalse(MDC.getCopyOfContextMap() != null && !MDC.getCopyOfContextMap().isEmpty());
    }

    /**
     * 测试用控制器。
     */
    @RestController
    static class PingController {

        @GetMapping("/ping")
        public Result<String> ping() {
            return Result.success("pong");
        }
    }
}
