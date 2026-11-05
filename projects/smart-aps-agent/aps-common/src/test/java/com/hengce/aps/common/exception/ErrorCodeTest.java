package com.hengce.aps.common.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ErrorCode 编码规约测试。
 */
class ErrorCodeTest {

    @Test
    void successCodeShouldBeZero() {
        assertEquals(0, ErrorCode.SUCCESS.getCode());
        assertTrue(ErrorCode.SUCCESS.isSuccess());
    }

    @Test
    void clientAndServerSegmentsShouldBeCorrect() {
        assertTrue(ErrorCode.BAD_REQUEST.isClientError());
        assertTrue(ErrorCode.NOT_FOUND.isClientError());
        assertTrue(ErrorCode.TOO_MANY_REQUESTS.isClientError());
        assertFalse(ErrorCode.BAD_REQUEST.isServerError());

        assertTrue(ErrorCode.INTERNAL_ERROR.isServerError());
        assertTrue(ErrorCode.SOLVER_NOT_AVAILABLE.isServerError());
        assertFalse(ErrorCode.INTERNAL_ERROR.isClientError());

        assertFalse(ErrorCode.SUCCESS.isClientError());
        assertFalse(ErrorCode.SUCCESS.isServerError());
    }

    @Test
    void allCodesShouldBeUniqueAndMessagesNotBlank() {
        Set<Integer> codes = new HashSet<>();
        Arrays.stream(ErrorCode.values()).forEach(errorCode -> {
            assertTrue(codes.add(errorCode.getCode()), "重复业务码: " + errorCode.getCode());
            assertFalse(errorCode.getMessage().isBlank(), "错误码提示不能为空: " + errorCode);
        });
    }
}
