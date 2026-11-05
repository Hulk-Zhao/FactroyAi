package com.hengce.aps.service.schedule.atp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hengce.aps.common.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 入参校验：非法参数必须收敛为 422/42200，合法参数按默认值归一化。
 */
class AtpRequestValidatorTest {

    private static final LocalDate REQUESTED = LocalDate.of(2026, 11, 6);

    private static final int DEFAULT_TIMEOUT = 10;

    @Test
    void missingBodyIsUnprocessable() {
        BizException exception = assertThrows(BizException.class,
                () -> AtpRequestValidator.validate(null, DEFAULT_TIMEOUT));
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void invalidQtyIsUnprocessable() {
        AtpQuery query = new AtpQuery("MAT-1", BigDecimal.valueOf(-1), REQUESTED, null, null,
                "FAST", null, null);
        BizException exception = assertThrows(BizException.class,
                () -> AtpRequestValidator.validate(query, DEFAULT_TIMEOUT));
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void invalidModeIsUnprocessable() {
        AtpQuery query = new AtpQuery("MAT-1", BigDecimal.TEN, REQUESTED, null, null,
                "TURBO", null, null);
        BizException exception = assertThrows(BizException.class,
                () -> AtpRequestValidator.validate(query, DEFAULT_TIMEOUT));
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void invalidTimeoutIsUnprocessable() {
        AtpQuery query = new AtpQuery("MAT-1", BigDecimal.TEN, REQUESTED, null, null,
                "ACCURATE", 0, null);
        BizException exception = assertThrows(BizException.class,
                () -> AtpRequestValidator.validate(query, DEFAULT_TIMEOUT));
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void blankMaterialIsUnprocessable() {
        AtpQuery query = new AtpQuery("  ", BigDecimal.TEN, REQUESTED, null, null,
                null, null, null);
        BizException exception = assertThrows(BizException.class,
                () -> AtpRequestValidator.validate(query, DEFAULT_TIMEOUT));
        assertEquals(42200, exception.getErrorCode().getCode());
    }

    @Test
    void validQueryUsesDefaults() {
        AtpQuery query = new AtpQuery(" MAT-1 ", BigDecimal.TEN, REQUESTED, null, null,
                null, null, null);
        AtpRequestValidator.NormalizedAtpQuery normalized =
                AtpRequestValidator.validate(query, DEFAULT_TIMEOUT);
        assertEquals("MAT-1", normalized.materialCode());
        assertEquals(AtpMode.FAST, normalized.mode());
        assertEquals(DEFAULT_TIMEOUT, normalized.timeoutSeconds());
        assertEquals(false, normalized.allowSplitBatch());
    }
}
