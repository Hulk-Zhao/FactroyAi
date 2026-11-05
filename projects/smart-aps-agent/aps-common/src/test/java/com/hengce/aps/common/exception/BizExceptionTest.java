package com.hengce.aps.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BizException 提示信息解析测试。
 */
class BizExceptionTest {

    @Test
    void shouldUseDefaultMessageWhenNoArgs() {
        BizException exception = new BizException(ErrorCode.NOT_FOUND);

        assertEquals(ErrorCode.NOT_FOUND.getMessage(), exception.getMessage());
        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void shouldUseSingleStringAsFullMessage() {
        BizException exception = new BizException(ErrorCode.NOT_FOUND, "订单不存在: SO-001");

        assertEquals("订单不存在: SO-001", exception.getMessage());
        assertEquals(1, exception.getArgs().length);
    }

    @Test
    void shouldAppendMultipleArgsToDefaultMessage() {
        BizException exception = new BizException(ErrorCode.BAD_REQUEST, "page", 0);

        assertEquals("请求参数不合法: page, 0", exception.getMessage());
    }

    @Test
    void businessExceptionShouldRemainCompatible() {
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "旧调用提示");
        });

        assertEquals("旧调用提示", exception.getMessage());
        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
    }
}
