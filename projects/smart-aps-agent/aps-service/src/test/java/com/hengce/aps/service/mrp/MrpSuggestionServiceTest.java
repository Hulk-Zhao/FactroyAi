package com.hengce.aps.service.mrp;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MrpSuggestionService} 单元测试：复用分析结果后只验证窗口过滤、排序、分页与参数校验。
 */
class MrpSuggestionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    private MrpAnalysisService mrpAnalysisService;

    private MrpSuggestionService service;

    @BeforeEach
    void setUp() {
        mrpAnalysisService = mock(MrpAnalysisService.class);
        service = new MrpSuggestionService(mrpAnalysisService, new MrpSuggestionCache(45));
    }

    @Test
    void filtersByHorizonIncludingBoundaryAndExcludingNull() {
        stub(List.of(
                item("RM-0004", NOW.plusDays(30)),
                item("RM-0002", NOW.plusDays(31)),
                item("RM-0001", null),
                item("RM-0003", NOW.plusDays(1))));
        PageQuery pageQuery = new PageQuery();
        pageQuery.setSize(200);

        PageResult<MrpAnalysisResponse.MrpSuggestionView> result =
                service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), pageQuery, NOW);

        assertEquals(List.of("RM-0003", "RM-0004"),
                result.items().stream().map(MrpAnalysisResponse.MrpSuggestionView::materialCode).toList());
        assertEquals(2, result.total());
        assertEquals(1, result.page());
        assertEquals(200, result.size());
    }

    @Test
    void keepsNegativeAvailableQtyAndShortageFormula() {
        MrpAnalysisResponse.MrpShortage shortage = new MrpAnalysisResponse.MrpShortage(
                "RM-0001", "铝板", new BigDecimal("120.000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("-30.000000"),
                new BigDecimal("120.000000"), NOW.plusDays(1), List.of("SO-001"),
                NOW.minusDays(1), new BigDecimal("120.000000"), null, List.of(), null);
        stub(List.of(shortage));

        PageResult<MrpAnalysisResponse.MrpSuggestionView> result =
                service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), new PageQuery(), NOW);

        assertEquals(1, result.items().size());
        assertEquals(0, new BigDecimal("-30").compareTo(result.items().get(0).availableQty()));
        assertEquals(0, new BigDecimal("120").compareTo(result.items().get(0).shortageQty()));
        assertEquals(NOW.minusDays(1), result.items().get(0).suggestedPurchaseDate());
    }

    @Test
    void sortsByMaterialCodeAsc() {
        stub(List.of(item("RM-0003", NOW.plusDays(1)),
                item("RM-0001", NOW.plusDays(1)),
                item("RM-0002", NOW.plusDays(1))));

        PageResult<MrpAnalysisResponse.MrpSuggestionView> result =
                service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), new PageQuery(), NOW);

        assertEquals(List.of("RM-0001", "RM-0002", "RM-0003"),
                result.items().stream().map(MrpAnalysisResponse.MrpSuggestionView::materialCode).toList());
    }

    @Test
    void pagesInMemoryAndClampsSizeAbove200() {
        List<MrpAnalysisResponse.MrpShortage> items = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            items.add(item("RM-" + String.format("%04d", index), NOW.plusDays(1)));
        }
        stub(items);
        PageQuery tooLarge = new PageQuery();
        tooLarge.setSize(500);

        PageResult<MrpAnalysisResponse.MrpSuggestionView> clamped = service.suggest(
                new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), tooLarge, NOW);
        assertEquals(200, clamped.items().size(), "size 上限按契约截断为 200");
        assertEquals(200, clamped.size());
        assertEquals(205, clamped.total());
        assertEquals(2, clamped.totalPages());

        PageQuery secondPage = new PageQuery();
        secondPage.setPage(2);
        secondPage.setSize(10);
        PageResult<MrpAnalysisResponse.MrpSuggestionView> page2 = service.suggest(
                new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), secondPage, NOW);
        assertEquals(10, page2.items().size());
        assertEquals("RM-0010", page2.items().get(0).materialCode());
        assertEquals(205, page2.total());
    }

    @Test
    void defaultHorizonIs30Days() {
        stub(List.of(item("RM-0001", NOW.plusDays(29)), item("RM-0002", NOW.plusDays(31))));

        PageResult<MrpAnalysisResponse.MrpSuggestionView> result =
                service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), new PageQuery(), NOW);

        assertEquals(1, result.items().size());
        assertEquals("RM-0001", result.items().get(0).materialCode());
    }

    @Test
    void reusesAnalysisWithSuggestionsAndForwardsOrderNos() {
        stub(List.of(item("RM-0001", NOW.plusDays(1))));

        service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of("SO-001", "SO-002"), 30),
                new PageQuery(), NOW);

        ArgumentCaptor<MrpAnalysisCommand> captor = ArgumentCaptor.forClass(MrpAnalysisCommand.class);
        verify(mrpAnalysisService).analyze(captor.capture());
        assertEquals(SANDBOX_ID, captor.getValue().sandboxId());
        assertEquals(List.of("SO-001", "SO-002"), captor.getValue().orderNos());
        assertEquals(true, captor.getValue().includeSuggestions(), "建议字段必须由分析服务输出");
        assertEquals(false, captor.getValue().blocking(), "采购建议不参与阻塞判定");
    }

    @Test
    void rejectsMissingSandboxId() {
        BizException exception = assertThrows(BizException.class, () ->
                service.suggest(new MrpSuggestionQuery(null, List.of(), null), new PageQuery(), NOW));
        assertEquals(40000, exception.getErrorCode().getCode());
    }

    @Test
    void rejectsOutOfRangeHorizonAndSort() {
        BizException horizon = assertThrows(BizException.class, () ->
                service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of(), 366), new PageQuery(), NOW));
        assertEquals(40000, horizon.getErrorCode().getCode());

        PageQuery sorted = new PageQuery();
        sorted.setSort("shortageQty,desc");
        BizException sort = assertThrows(BizException.class, () ->
                service.suggest(new MrpSuggestionQuery(SANDBOX_ID, List.of(), null), sorted, NOW));
        assertEquals(40000, sort.getErrorCode().getCode());
    }

    private void stub(List<MrpAnalysisResponse.MrpShortage> shortages) {
        when(mrpAnalysisService.analyze(any())).thenReturn(new MrpAnalysisResponse(SANDBOX_ID, NOW,
                MrpStatus.SHORTAGE, false, false, true, 1, List.of(), List.of(), shortages));
    }

    private MrpAnalysisResponse.MrpShortage item(String materialCode, OffsetDateTime earliestRequiredAt) {
        return new MrpAnalysisResponse.MrpShortage(materialCode, materialCode, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, earliestRequiredAt, List.of("SO-001"), earliestRequiredAt,
                BigDecimal.ONE, null, List.of(), null);
    }
}
