package com.hengce.aps.web.idempotency;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.trace.TraceContext;
import com.hengce.aps.web.advice.RestExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等拦截器测试：首次放行、重复 409、非法键 400、失败释放、GET 不拦截。
 */
class IdempotencyInterceptorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId("idem-trace-0001");
        MDC.put(TraceContext.TENANT_MDC_KEY, "tenant-test");
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new RestExceptionHandler())
                .addInterceptors(new IdempotencyInterceptor(new InMemoryIdempotencyStore()))
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        MDC.clear();
    }

    @Test
    void shouldAllowRequestsWithoutIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/test/items")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/test/items")).andExpect(status().isOk());
    }

    @Test
    void shouldRejectDuplicateIdempotencyKeyWith409() throws Exception {
        mockMvc.perform(post("/api/v1/test/items")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "order-create-0001"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/test/items")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "order-create-0001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_CONFLICT.getCode()));
    }

    @Test
    void shouldRejectIllegalKeyWith400() throws Exception {
        mockMvc.perform(post("/api/v1/test/items")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    void shouldReleaseKeyWhenRequestFails() throws Exception {
        mockMvc.perform(post("/api/v1/test/failing")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "retry-key-0001"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/v1/test/failing")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "retry-key-0001"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNPROCESSABLE_ENTITY.getCode()));
    }

    @Test
    void shouldNotInterceptReadRequests() throws Exception {
        mockMvc.perform(get("/api/v1/test/items")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "read-only-0001"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/test/items")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, "read-only-0001"))
                .andExpect(status().isOk());
    }

    /**
     * 测试用控制器。
     */
    @RestController
    @RequestMapping("/api/v1/test")
    static class TestController {

        @PostMapping("/items")
        public Result<String> create() {
            return Result.success("created");
        }

        @GetMapping("/items")
        public Result<String> list() {
            return Result.success("list");
        }

        @PostMapping("/failing")
        public Result<Void> failing() {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "业务规则不满足");
        }
    }
}
