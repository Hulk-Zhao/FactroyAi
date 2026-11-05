package com.hengce.aps.web.importexport;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.importexport.EntityDataExporter;
import com.hengce.aps.service.importexport.ImportEntity;
import com.hengce.aps.service.importexport.ImportJobService;
import com.hengce.aps.service.importexport.ImportJobStatus;
import com.hengce.aps.service.importexport.ImportJobView;
import com.hengce.aps.service.importexport.ImportMode;
import com.hengce.aps.service.importexport.ImportResult;
import com.hengce.aps.service.importexport.ImportService;
import com.hengce.aps.web.advice.RestExceptionHandler;
import com.hengce.aps.web.controller.importexport.ImportExportController;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 导入导出控制器契约测试（C38/C39）：异步 202 形状、任务查询/取消路由与错误码映射。
 */
class ImportExportControllerTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ImportService importService;

    private ImportJobService importJobService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        importService = mock(ImportService.class);
        importJobService = mock(ImportJobService.class);
        EntityDataExporter exporter = mock(EntityDataExporter.class);
        when(importService.resolveEntity("customers")).thenReturn(ImportEntity.CUSTOMERS);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ImportExportController(importService, exporter, importJobService))
                .setControllerAdvice(new RestExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void oversizedFileReturns422And42200() throws Exception {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "customers-big.xlsx", XLSX, oversized);

        mockMvc.perform(multipart("/api/v1/customers/import").file(file).param("mode", "async"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(42200))
                .andExpect(jsonPath("$.message").value(containsString("5MB")));
    }

    @Test
    void asyncSubmitReturns202WithJobView() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(importJobService.submit(eq(ImportEntity.CUSTOMERS), any(), any(), any()))
                .thenReturn(new ImportJobView(jobId, "customers", "async", ImportJobStatus.PENDING,
                        0, 0, 0, 0, 0, 0, List.of(), false, "sha256:abc",
                        OffsetDateTime.of(2026, 11, 4, 8, 0, 0, 0, ZoneOffset.UTC), null, null));
        MockMultipartFile file = new MockMultipartFile("file", "customers.xlsx", XLSX, new byte[]{1, 2});

        mockMvc.perform(multipart("/api/v1/customers/import").file(file).param("mode", "async")
                        .header("X-Import-Token", "W9-TOKEN-0001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.entity").value("customers"))
                .andExpect(jsonPath("$.mode").value("async"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void jobQueryReturnsSameView() throws Exception {
        UUID jobId = UUID.randomUUID();
        ImportJobView view = new ImportJobView(jobId, "customers", "async", ImportJobStatus.SUCCEEDED,
                1, 1, 1, 0, 0, 0, List.of(), true, "sha256:abc",
                OffsetDateTime.of(2026, 11, 4, 8, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 11, 4, 8, 0, 1, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 11, 4, 8, 0, 2, 0, ZoneOffset.UTC));
        when(importJobService.find(eq(ImportEntity.CUSTOMERS), eq(jobId))).thenReturn(view);

        mockMvc.perform(get("/api/v1/customers/import/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.totalRows").value(1));
    }

    @Test
    void crossEntityJobQueryReturns40400() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(importJobService.find(eq(ImportEntity.CUSTOMERS), eq(jobId))).thenThrow(
                new BizException(ErrorCode.NOT_FOUND, "导入任务不存在: " + jobId));

        mockMvc.perform(get("/api/v1/customers/import/jobs/{jobId}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void cancelReturns202AndTerminalCancelConflicts() throws Exception {
        UUID jobId = UUID.randomUUID();
        ImportJobView cancelled = new ImportJobView(jobId, "customers", "async", ImportJobStatus.CANCELLED,
                0, 0, 0, 0, 0, 0, List.of(), false, "sha256:abc",
                OffsetDateTime.of(2026, 11, 4, 8, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 11, 4, 8, 0, 1, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 11, 4, 8, 0, 1, 0, ZoneOffset.UTC));
        when(importJobService.cancel(eq(ImportEntity.CUSTOMERS), eq(jobId))).thenReturn(cancelled);

        mockMvc.perform(post("/api/v1/customers/import/jobs/{jobId}/cancel", jobId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        UUID finishedJob = UUID.randomUUID();
        when(importJobService.cancel(eq(ImportEntity.CUSTOMERS), eq(finishedJob))).thenThrow(
                new BizException(ErrorCode.STATE_CONFLICT, "导入任务已结束，无法取消"));
        mockMvc.perform(post("/api/v1/customers/import/jobs/{jobId}/cancel", finishedJob))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    void syncPreviewKeepsResultEnvelopeAndModeValidation() throws Exception {
        byte[] fileBytes = new byte[]{1, 2};
        when(importService.importFile(eq(ImportEntity.CUSTOMERS), eq(ImportMode.PREVIEW), any(),
                any(), eq(2L)))
                .thenReturn(new ImportResult("customers", ImportMode.PREVIEW, 1, 1, 0, 0, 0, 0,
                        false, false, List.of(), List.of()));
        MockMultipartFile file = new MockMultipartFile("file", "customers.xlsx", XLSX, fileBytes);

        mockMvc.perform(multipart("/api/v1/customers/import").file(file).param("mode", "preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("PREVIEW"));

        mockMvc.perform(multipart("/api/v1/customers/import").file(file).param("mode", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
