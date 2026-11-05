package com.hengce.aps.web.advice;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.trace.TraceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常映射测试：至少覆盖 400/404/409/422/500。
 */
class RestExceptionHandlerTest {

    private static final String TEST_TRACE_ID = "unit-trace-0001";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId(TEST_TRACE_ID);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void shouldMapValidationFailureTo400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("name: 不能为空"))
                .andExpect(jsonPath("$.traceId").value(TEST_TRACE_ID));
    }

    @Test
    void shouldMapNotFoundBizExceptionTo404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("订单不存在: SO-001"))
                .andExpect(jsonPath("$.traceId").value(TEST_TRACE_ID));
    }

    @Test
    void shouldMapIdempotencyConflictTo409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_CONFLICT.getCode()))
                .andExpect(jsonPath("$.traceId").value(TEST_TRACE_ID));
    }

    @Test
    void shouldMapUnprocessableEntityTo422() throws Exception {
        mockMvc.perform(get("/test/unprocessable"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNPROCESSABLE_ENTITY.getCode()))
                .andExpect(jsonPath("$.message").value("排产数量必须为正数"))
                .andExpect(jsonPath("$.traceId").value(TEST_TRACE_ID));
    }

    @Test
    void shouldHideInternalExceptionDetailAndReturn500() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()))
                .andExpect(jsonPath("$.traceId").value(TEST_TRACE_ID));
    }

    @Test
    void shouldKeepSuccessEnvelopeUntouched() throws Exception {
        mockMvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    /**
     * 测试用控制器，仅用于触发异常映射。
     */
    @RestController
    static class TestController {

        @PostMapping("/test/validate")
        public Result<Void> validate(@Valid @RequestBody Payload payload) {
            return Result.success();
        }

        @GetMapping("/test/not-found")
        public Result<Void> notFound() {
            throw new BizException(ErrorCode.NOT_FOUND, "订单不存在: SO-001");
        }

        @GetMapping("/test/conflict")
        public Result<Void> conflict() {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }

        @GetMapping("/test/unprocessable")
        public Result<Void> unprocessable() {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "排产数量必须为正数");
        }

        @GetMapping("/test/error")
        public Result<Void> error() {
            throw new IllegalStateException("内部堆栈不应出现在响应");
        }

        @GetMapping("/test/ok")
        public Result<String> ok() {
            return Result.success("ok");
        }
    }

    /**
     * 校验用请求体。
     *
     * @param name 名称
     */
    record Payload(@NotBlank(message = "不能为空") String name) {
    }
}
