package com.hengce.aps.web.mrp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.mrp.InventoryService;
import com.hengce.aps.service.mrp.InventoryUpsertResult;
import com.hengce.aps.service.mrp.MrpAnalysisResponse;
import com.hengce.aps.service.mrp.MrpAnalysisService;
import com.hengce.aps.service.mrp.MrpStatus;
import com.hengce.aps.service.mrp.MrpSuggestionCache;
import com.hengce.aps.service.mrp.MrpSuggestionService;
import com.hengce.aps.web.advice.RestExceptionHandler;
import com.hengce.aps.web.controller.mrp.InventoryController;
import com.hengce.aps.web.controller.mrp.MrpController;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MRP / 库存控制器契约测试：路由、统一响应结构与错误码映射，不依赖真实数据库。
 */
class MrpControllerTest {

    private MrpAnalysisService mrpAnalysisService;

    private InventoryService inventoryService;

    private MrpSuggestionService mrpSuggestionService;

    private MockMvc mockMvc;

    private UUID sandboxId;

    @BeforeEach
    void setUp() {
        mrpAnalysisService = mock(MrpAnalysisService.class);
        inventoryService = mock(InventoryService.class);
        mrpSuggestionService = new MrpSuggestionService(mrpAnalysisService, new MrpSuggestionCache(45));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MrpController(mrpAnalysisService, mrpSuggestionService),
                        new InventoryController(inventoryService))
                .setControllerAdvice(new RestExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        sandboxId = UUID.randomUUID();
    }

    @Test
    void analysisReturnsC25Fields() throws Exception {
        MrpAnalysisResponse response = new MrpAnalysisResponse(sandboxId,
                OffsetDateTime.of(2026, 11, 4, 4, 0, 0, 0, ZoneOffset.UTC), MrpStatus.SHORTAGE,
                true, true, true, 1, List.of(),
                List.of(new MrpAnalysisResponse.MrpOrderResult("SO-001", "FG-01", new BigDecimal("10"),
                        LocalDate.of(2026, 11, 10), false, MrpStatus.SHORTAGE, 1, 1,
                        List.of(new MrpAnalysisResponse.MrpOrderShortage("RM-01", "原料",
                                new BigDecimal("10.000000"), new BigDecimal("4.000000"),
                                new BigDecimal("6.000000"))))),
                List.of(new MrpAnalysisResponse.MrpShortage("RM-01", "原料", new BigDecimal("10.000000"),
                        new BigDecimal("8.000000"), new BigDecimal("2.000000"), new BigDecimal("0.000000"),
                        new BigDecimal("4.000000"), new BigDecimal("6.000000"),
                        OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.UTC),
                        List.of("SO-001"),
                        OffsetDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneOffset.UTC),
                        new BigDecimal("6.000000"), "SUP-01",
                        List.of(new MrpAnalysisResponse.MrpSubstitute("RM-02", 1, "1:1",
                                new BigDecimal("3.000000"))),
                        OffsetDateTime.of(2026, 11, 12, 0, 0, 0, 0, ZoneOffset.UTC))));
        when(mrpAnalysisService.analyze(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/mrp/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sandboxId\":\"" + sandboxId
                                + "\",\"orderNos\":[\"SO-001\"],\"blocking\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SHORTAGE"))
                .andExpect(jsonPath("$.data.blocked").value(true))
                .andExpect(jsonPath("$.data.orders[0].orderNo").value("SO-001"))
                .andExpect(jsonPath("$.data.orders[0].shortages[0].materialCode").value("RM-01"))
                .andExpect(jsonPath("$.data.orders[0].kitted").value(false))
                .andExpect(jsonPath("$.data.orders[0].kittingStatus").value("SHORTAGE"))
                .andExpect(jsonPath("$.data.orders[0].shortageItemCount").value(1))
                .andExpect(jsonPath("$.data.orders[0].totalItemCount").value(1))
                .andExpect(jsonPath("$.data.shortages[0].supplierCode").value("SUP-01"))
                .andExpect(jsonPath("$.data.shortages[0].substitutes[0].materialCode").value("RM-02"))
                .andExpect(jsonPath("$.data.shortages[0].substitutes[0].priority").value(1))
                .andExpect(jsonPath("$.data.shortages[0].substitutes[0].ratio").value("1:1"))
                .andExpect(jsonPath("$.data.shortages[0].substitutes[0].availableQty").value(3.0))
                .andExpect(jsonPath("$.data.shortages[0].expectedArrival").value("2026-11-12T00:00:00Z"))
                .andExpect(jsonPath("$.data.shortages[0].onHandQty").value(8.0))
                .andExpect(jsonPath("$.data.shortages[0].transitQty").value(2.0))
                .andExpect(jsonPath("$.data.shortages[0].safetyStock").value(0.0))
                .andExpect(jsonPath("$.data.shortages[0].earliestRequiredAt").value("2026-11-10T00:00:00Z"));
    }

    @Test
    void analysisRejectsMissingSandboxId() throws Exception {
        mockMvc.perform(post("/api/v1/mrp/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNos\":[\"SO-001\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void inventoryUpsertReturnsCounts() throws Exception {
        when(inventoryService.upsert(any())).thenReturn(new InventoryUpsertResult(1, 0));

        mockMvc.perform(put("/api/v1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"materialCode\":\"RM-01\",\"warehouseCode\":\"DEFAULT\","
                                + "\"onHandQty\":10,\"safetyStock\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(0));
    }

    @Test
    void suggestionsReturnC34FieldsWithHorizonAndPaging() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(mrpAnalysisService.analyze(any())).thenReturn(new MrpAnalysisResponse(sandboxId,
                now, MrpStatus.SHORTAGE, false, false, true, 1, List.of(), List.of(),
                List.of(
                        new MrpAnalysisResponse.MrpShortage("RM-0002", "物料2", new BigDecimal("8.000000"),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                new BigDecimal("0.000000"), new BigDecimal("8.000000"), now.plusDays(40),
                                List.of("SO-002"), now.plusDays(35), new BigDecimal("8.000000"), null,
                                List.of(), null),
                        new MrpAnalysisResponse.MrpShortage("RM-0001", "物料1", new BigDecimal("120.000000"),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                new BigDecimal("-30.000000"), new BigDecimal("120.000000"), now.plusDays(2),
                                List.of("SO-001"), now.plusDays(1), new BigDecimal("120.000000"), null,
                                List.of(), null),
                        new MrpAnalysisResponse.MrpShortage("RM-0003", "物料3", new BigDecimal("5.000000"),
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                new BigDecimal("0.000000"), new BigDecimal("5.000000"), now.plusDays(5),
                                List.of("SO-003"), now.plusDays(3), new BigDecimal("5.000000"), null,
                                List.of(), null))));

        mockMvc.perform(get("/api/v1/mrp/suggestions")
                        .param("sandboxId", sandboxId.toString())
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].materialCode").value("RM-0001"))
                .andExpect(jsonPath("$.data.items[0].availableQty").value(-30.0))
                .andExpect(jsonPath("$.data.items[0].shortageQty").value(120.0))
                .andExpect(jsonPath("$.data.items[0].affectedOrderNos[0]").value("SO-001"))
                .andExpect(jsonPath("$.data.items[0].supplierCode").value(nullValue()));

        mockMvc.perform(get("/api/v1/mrp/suggestions")
                        .param("sandboxId", sandboxId.toString())
                        .param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].materialCode").value("RM-0003"));
    }

    @Test
    void suggestionsEmptyResultReturnsEmptyItems() throws Exception {
        when(mrpAnalysisService.analyze(any())).thenReturn(new MrpAnalysisResponse(sandboxId,
                OffsetDateTime.now(ZoneOffset.UTC), MrpStatus.FULL, false, false, true, 0,
                List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/mrp/suggestions").param("sandboxId", sandboxId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void suggestionsRejectMissingOrInvalidSandboxId() throws Exception {
        mockMvc.perform(get("/api/v1/mrp/suggestions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        mockMvc.perform(get("/api/v1/mrp/suggestions").param("sandboxId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void suggestionsRejectSortAndOutOfRangeHorizon() throws Exception {
        mockMvc.perform(get("/api/v1/mrp/suggestions")
                        .param("sandboxId", sandboxId.toString()).param("sort", "shortageQty,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        mockMvc.perform(get("/api/v1/mrp/suggestions")
                        .param("sandboxId", sandboxId.toString()).param("horizonDays", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        mockMvc.perform(get("/api/v1/mrp/suggestions")
                        .param("sandboxId", sandboxId.toString()).param("horizonDays", "366"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
