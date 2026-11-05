package com.hengce.aps.common.api;

import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Result 统一响应单元测试。
 */
class ResultTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void successShouldCarryTraceId() {
        TraceContext.setTraceId("trace-12345678");
        Result<String> result = Result.success("payload");

        assertEquals(0, result.code());
        assertEquals("success", result.message());
        assertEquals("payload", result.data());
        assertEquals("trace-12345678", result.traceId());
        assertTrue(result.isSuccess());
    }

    @Test
    void emptySuccessShouldHaveNullData() {
        Result<Void> result = Result.success();

        assertEquals(0, result.code());
        assertNull(result.data());
        assertTrue(result.isSuccess());
    }

    @Test
    void failureShouldUseErrorCodeAndTraceId() {
        TraceContext.setTraceId("trace-abcdefgh");
        Result<Void> result = Result.failure(ErrorCode.NOT_FOUND);

        assertEquals(ErrorCode.NOT_FOUND.getCode(), result.code());
        assertEquals(ErrorCode.NOT_FOUND.getMessage(), result.message());
        assertNull(result.data());
        assertEquals("trace-abcdefgh", result.traceId());
        assertFalse(result.isSuccess());
    }

    @Test
    void failureShouldAllowCustomMessage() {
        Result<Void> result = Result.failure(ErrorCode.IDEMPOTENCY_CONFLICT, "幂等键重复");

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT.getCode(), result.code());
        assertEquals("幂等键重复", result.message());
    }
}
