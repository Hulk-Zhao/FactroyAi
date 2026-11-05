package com.hengce.aps.web.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.trace.TraceContext;
import com.hengce.aps.service.schedule.TaskAdjustService;
import com.hengce.aps.web.advice.RestExceptionHandler;
import com.hengce.aps.web.controller.contract.GanttContractController;
import com.hengce.aps.web.idempotency.IdempotencyInterceptor;
import com.hengce.aps.web.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * adjust 端点幂等：同 Idempotency-Key 重复请求只放行一次（第二次 40900）。
 */
class GanttAdjustIdempotencyTest {

    private static final String KEY = "adjust-split-0001";

    private static final String BODY =
            "{\"operation\":\"SPLIT\",\"version\":1,\"splitQuantities\":[4,6],\"reason\":\"拆分\"}";

    private MockMvc mockMvc;

    private TaskAdjustService taskAdjustService;

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId("adjust-idem-0001");
        taskAdjustService = mock(TaskAdjustService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GanttContractController(taskAdjustService))
                .setControllerAdvice(new RestExceptionHandler())
                .addInterceptors(new IdempotencyInterceptor(new InMemoryIdempotencyStore()))
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("同 Idempotency-Key 第二次请求返回 40900，服务只被调用一次")
    void duplicateIdempotencyKeyAppliesOnce() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/10/adjust")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/tasks/10/adjust")
                        .header(ApsConstants.HEADER_IDEMPOTENCY_KEY, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_CONFLICT.getCode()));

        verify(taskAdjustService, times(1)).adjust(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("缺少 Idempotency-Key 时控制器放行，由服务返回 40000")
    void missingIdempotencyKeyReachesService() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/10/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
        verify(taskAdjustService).adjust(anyLong(), any(), org.mockito.ArgumentMatchers.isNull());
    }
}
