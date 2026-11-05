package com.hengce.aps.service.schedule.ctp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.solver.api.ctp.CtpPriority;
import com.hengce.aps.solver.api.ctp.CtpRequest;
import com.hengce.aps.solver.api.ctp.CtpResult;
import com.hengce.aps.solver.api.ctp.CtpVerdict;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * C53 编排：物料证据在前、容量证据在后，共用 8 条上限；物料缺料时不求解。
 */
class CtpInquiryCapacityMergeTest {

    private static final LocalDate REQUESTED = LocalDate.of(2026, 11, 6);

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void capacityEvidenceIsAppendedAndCapKeepsIt() {
        CtpDataReader dataReader = mock(CtpDataReader.class);
        CtpMaterialGate materialGate = mock(CtpMaterialGate.class);
        CtpCapacityVerifier capacityVerifier = mock(CtpCapacityVerifier.class);
        when(dataReader.findMaterialsByCodes(any())).thenReturn(Map.of("M1", material()));
        when(materialGate.evaluate(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(gate(LocalDate.of(2026, 11, 5), eightMaterialEvidence()));
        when(capacityVerifier.verify(any(), any(), any(), any(), any()))
                .thenReturn(promised());
        CtpInquiryService service = new CtpInquiryService(dataReader, materialGate,
                capacityVerifier, "Asia/Shanghai", 168, 15);

        CtpResult result = service.inquire(request());

        assertThat(result.verdict()).isEqualTo(CtpVerdict.PROMISED);
        assertThat(result.promisedDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(result.promisedQty()).isEqualByComparingTo("2");
        assertThat(result.capacityVerifiedAt()).isNotNull();
        assertThat(result.mainPlanWritten()).isFalse();
        assertThat(result.evidence()).hasSize(8);
        assertThat(result.evidence().subList(0, 7))
                .extracting(item -> item.get("kind"))
                .containsExactly("M1", "M2", "M3", "M4", "M5", "M6", "M7");
        assertThat(result.evidence().get(7).get("kind")).isEqualTo("PROMISED");
    }

    @Test
    void materialShortageDoesNotInvokeCapacitySolver() {
        CtpDataReader dataReader = mock(CtpDataReader.class);
        CtpMaterialGate materialGate = mock(CtpMaterialGate.class);
        CtpCapacityVerifier capacityVerifier = mock(CtpCapacityVerifier.class);
        when(dataReader.findMaterialsByCodes(any())).thenReturn(Map.of("M1", material()));
        when(materialGate.evaluate(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(gate(LocalDate.of(2026, 11, 8), List.of()));
        CtpInquiryService service = new CtpInquiryService(dataReader, materialGate,
                capacityVerifier, "Asia/Shanghai", 168, 15);

        CtpResult result = service.inquire(request());

        assertThat(result.verdict()).isEqualTo(CtpVerdict.UNPROMISABLE);
        assertThat(result.promisedDate()).isNull();
        assertThat(result.mainPlanWritten()).isFalse();
        verify(capacityVerifier, never()).verify(any(), any(), any(), any(), any());
    }

    private static CtpRequest request() {
        return new CtpRequest("M1", BigDecimal.valueOf(2), REQUESTED, null, CtpPriority.NORMAL,
                false, false, null, 5);
    }

    private static Material material() {
        Material material = new Material();
        material.setCode("M1");
        material.setName("物料1");
        return material;
    }

    private static CtpMaterialGate.Result gate(LocalDate materialReadyAt,
                                               List<Map<String, Object>> evidence) {
        return new CtpMaterialGate.Result(materialReadyAt, 1, false, false, false,
                List.of(), List.of(), List.of(), evidence, List.of("MATERIAL:EVIDENCE"));
    }

    private static List<Map<String, Object>> eightMaterialEvidence() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "M" + index);
            item.put("taskIds", List.of());
            item.put("detail", "物料证据 " + index);
            items.add(item);
        }
        return items;
    }

    private static CtpCapacityVerifier.CapacityOutcome promised() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("kind", "PROMISED");
        evidence.put("taskIds", List.of());
        evidence.put("resourceCode", "R1");
        evidence.put("detail", "容量验证通过");
        return new CtpCapacityVerifier.CapacityOutcome(CtpVerdict.PROMISED,
                LocalDate.of(2026, 11, 5), BigDecimal.valueOf(2), NOW, null, evidence,
                List.of("CAPACITY:LOWER_BOUND"), "容量验证通过");
    }
}
