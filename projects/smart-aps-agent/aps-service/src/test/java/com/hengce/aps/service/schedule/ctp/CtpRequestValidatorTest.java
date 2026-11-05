package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.solver.api.ctp.CtpMaterialPolicy;
import com.hengce.aps.solver.api.ctp.CtpRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CTP 询单入参校验测试：边界、替代策略合并、限时范围。
 */
class CtpRequestValidatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 10);

    @Test
    void rejectsMissingOrIllegalFields() {
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(null, 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request(null, "10", DATE, null, null, null), 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", null, DATE, null, null, null), 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", "0", DATE, null, null, null), 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", "-1", DATE, null, null, null), 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", "1000000001", DATE, null, null, null), 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", "1", null, null, null, null), 15));
    }

    @Test
    void rejectsTimeoutOutsideOneToFifteenSeconds() {
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", "1", DATE, null, null, 0), 15));
        assertThrows(BizException.class, () -> CtpRequestValidator.validate(
                request("M-1", "1", DATE, null, null, 16), 15));
    }

    @Test
    void mergesSubstitutePolicyFromEitherField() {
        CtpRequestValidator.NormalizedCtpRequest strict = CtpRequestValidator.validate(
                request("M-1", "10", DATE, null, false, null), 15);
        assertFalse(strict.allowSubstitute());
        assertEquals(15, strict.timeoutSeconds());

        CtpRequestValidator.NormalizedCtpRequest byFlag = CtpRequestValidator.validate(
                request("M-1", "10", DATE, null, true, null), 15);
        assertTrue(byFlag.allowSubstitute());

        CtpRequestValidator.NormalizedCtpRequest byPolicy = CtpRequestValidator.validate(
                new CtpRequest("M-1", new BigDecimal("10"), DATE, null, null, null, null,
                        CtpMaterialPolicy.SUBSTITUTE, 5), 15);
        assertTrue(byPolicy.allowSubstitute());
        assertEquals(5, byPolicy.timeoutSeconds());
    }

    @Test
    void trimsCodesAndKeepsQty() {
        CtpRequestValidator.NormalizedCtpRequest normalized = CtpRequestValidator.validate(
                request("  M-1  ", "12.5", DATE, "  C-1  ", null, null), 15);
        assertEquals("M-1", normalized.materialCode());
        assertEquals("C-1", normalized.customerCode());
        assertEquals(0, new BigDecimal("12.5").compareTo(normalized.qty()));
    }

    private CtpRequest request(String code, String qty, LocalDate date, String customer,
                               Boolean allowSubstitute, Integer timeout) {
        return new CtpRequest(code, qty == null ? null : new BigDecimal(qty), date, customer, null,
                null, allowSubstitute, null, timeout);
    }
}
