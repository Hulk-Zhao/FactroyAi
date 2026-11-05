package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.solver.api.ctp.CtpRequest;
import com.hengce.aps.solver.api.ctp.CtpResult;
import com.hengce.aps.solver.api.ctp.CtpVerdict;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 询单服务测试：三档结论边界、horizonEnd、证据上限与“绝不返回承诺日期”。
 */
class CtpInquiryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final LocalDate TODAY = LocalDate.now(ZONE);

    private CtpDataReader reader;

    private CtpMaterialGate gate;

    private CtpInquiryService service;

    private Material material;

    @BeforeEach
    void setUp() {
        reader = mock(CtpDataReader.class);
        gate = mock(CtpMaterialGate.class);
        service = new CtpInquiryService(reader, gate, "Asia/Shanghai", 168, 15);
        material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode("RAW-BMS-001");
        material.setName("焊装原料");
        when(reader.findMaterialsByCodes(any())).thenReturn(Map.of("RAW-BMS-001", material));
    }

    @Test
    void returnsOutOfHorizonWithHorizonEndAndSkipsMaterialGate() {
        CtpResult result = service.inquire(request("RAW-BMS-001", "10", TODAY.plusDays(30)));

        assertEquals(CtpVerdict.OUT_OF_HORIZON, result.verdict());
        assertNotNull(result.horizonEnd());
        assertNull(result.materialReadyAt());
        assertFalse(result.mainPlanWritten());
        assertTrue(result.evidence().size() <= 8);
        assertTrue(result.evidence().stream()
                .anyMatch(item -> "HORIZON_EXCEEDED".equals(item.get("kind"))));
        verify(gate, never()).evaluate(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void returnsUnverifiedWhenMaterialReadyButCapacityNotVerified() {
        CtpMaterialGate.Result gateResult = gateResult(TODAY, 0, false, false, false, List.of(),
                List.of(), List.of(), List.of());
        when(gate.evaluate(any(), any(), any(), any(), any(Boolean.class))).thenReturn(gateResult);

        CtpResult result = service.inquire(request("RAW-BMS-001", "40", TODAY.plusDays(2)));

        assertEquals(CtpVerdict.UNVERIFIED, result.verdict());
        assertEquals(TODAY, result.materialReadyAt());
        assertNull(result.promisedDate());
        assertNull(result.promisedQty());
        assertNull(result.capacityVerifiedAt());
        assertFalse(result.mainPlanWritten());
        assertEquals(0, result.material().get("bomDepth"));
    }

    @Test
    void returnsUnpromisableWithNoReadyDateWhenMaterialCannotBeReady() {
        CtpMaterialGate.Result gateResult = gateResult(null, 1, false, false, false, List.of(),
                List.of(), List.of(), List.of());
        when(gate.evaluate(any(), any(), any(), any(), any(Boolean.class))).thenReturn(gateResult);

        CtpResult result = service.inquire(request("RAW-BMS-001", "40", TODAY.plusDays(2)));

        assertEquals(CtpVerdict.UNPROMISABLE, result.verdict());
        assertNull(result.materialReadyAt());
        assertNull(result.promisedDate());
        assertTrue(result.message().contains("不可确定"));
    }

    @Test
    void returnsUnpromisableWhenReadyDateIsLaterThanRequested() {
        CtpMaterialGate.Result gateResult = gateResult(TODAY.plusDays(5), 1, false, false, false,
                List.of(), List.of(), List.of(), List.of());
        when(gate.evaluate(any(), any(), any(), any(), any(Boolean.class))).thenReturn(gateResult);

        CtpResult result = service.inquire(request("RAW-BMS-001", "30", TODAY.plusDays(2)));

        assertEquals(CtpVerdict.UNPROMISABLE, result.verdict());
        assertEquals(TODAY.plusDays(5), result.materialReadyAt());
        assertNull(result.promisedDate());
        assertTrue(result.message().contains("晚于请求交期"));
    }

    @Test
    void returnsNoMaterialWhenMasterDataMissing() {
        CtpMaterialGate.Result gateResult = gateResult(null, 2, true, false, false,
                List.of(CtpMaterialGate.FLAG_MISSING_MASTER_DATA), List.of(), List.of(), List.of());
        when(gate.evaluate(any(), any(), any(), any(), any(Boolean.class))).thenReturn(gateResult);

        CtpResult result = service.inquire(request("RAW-BMS-001", "5", TODAY.plusDays(2)));

        assertEquals(CtpVerdict.NO_MATERIAL, result.verdict());
        assertTrue(result.message().contains("MISSING_MASTER_DATA"));
    }

    @Test
    void keepsEvidenceWithinEightItems() {
        List<Map<String, Object>> nine = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "MATERIAL_SHORTAGE");
            item.put("detail", "缺口 " + index);
            nine.add(item);
        }
        CtpMaterialGate.Result gateResult = gateResult(null, 3, false, false, false, List.of(),
                List.of(), List.of(), nine);
        when(gate.evaluate(any(), any(), any(), any(), any(Boolean.class))).thenReturn(gateResult);

        CtpResult result = service.inquire(request("RAW-BMS-001", "40", TODAY.plusDays(2)));

        assertEquals(8, result.evidence().size());
    }

    @Test
    void rejectsUnknownMaterialAndIllegalQty() {
        when(reader.findMaterialsByCodes(any())).thenReturn(Map.of());
        BizException notFound = assertThrows(BizException.class,
                () -> service.inquire(request("RAW-UNKNOWN", "10", TODAY.plusDays(1))));
        assertEquals(ErrorCode.NOT_FOUND, notFound.getErrorCode());

        when(reader.findMaterialsByCodes(any())).thenReturn(Map.of("RAW-BMS-001", material));
        BizException illegal = assertThrows(BizException.class,
                () -> service.inquire(request("RAW-BMS-001", "0", TODAY.plusDays(1))));
        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, illegal.getErrorCode());
    }

    private CtpRequest request(String code, String qty, LocalDate date) {
        return new CtpRequest(code, new BigDecimal(qty), date, null, null, null, null, null, null);
    }

    private CtpMaterialGate.Result gateResult(LocalDate readyAt, int bomDepth, boolean missing,
                                              boolean depthLimited, boolean cycle,
                                              List<String> limitFlags,
                                              List<CtpMaterialGate.ShortageLine> shortages,
                                              List<CtpMaterialGate.SubstituteLine> substitutes,
                                              List<Map<String, Object>> evidence) {
        return new CtpMaterialGate.Result(readyAt, bomDepth, missing, depthLimited, cycle,
                limitFlags, shortages, substitutes, evidence, List.of("ASSUMPTION"));
    }
}
