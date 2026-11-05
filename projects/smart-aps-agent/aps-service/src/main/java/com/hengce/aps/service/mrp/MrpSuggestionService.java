package com.hengce.aps.service.mrp;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * MRP 采购建议查询（C34）。
 *
 * <p>本服务不重新实现齐套判定，直接复用 {@link MrpAnalysisService#analyze(MrpAnalysisCommand)}
 * 的缺料明细（{@code shortageQty = requiredQty - max(availableQty, 0)}），在其上做三件事：
 * 按 {@code horizonDays} 过滤 {@code earliestRequiredAt}、固定按 {@code materialCode} 升序、
 * 内存分页。因此建议口径与 {@code POST /mrp/analysis} 恒等，不存在两套判定。</p>
 *
 * <p>{@code earliestRequiredAt} 为空的缺料（订单交期缺失的异常数据）不满足
 * “{@code <= now + horizonDays}”，按窗口口径排除，并在响应总数中不计入。</p>
 */
@Service
public class MrpSuggestionService {

    /**
     * 缺省需求时间窗口（天）。
     */
    static final int DEFAULT_HORIZON_DAYS = 30;

    /**
     * 需求时间窗口下限（天）。
     */
    static final int MIN_HORIZON_DAYS = 1;

    /**
     * 需求时间窗口上限（天）。
     */
    static final int MAX_HORIZON_DAYS = 365;

    private final MrpAnalysisService mrpAnalysisService;

    private final MrpSuggestionCache suggestionCache;

    /**
     * 构造服务。
     *
     * @param mrpAnalysisService MRP 齐套分析服务（复用其缺料明细）
     * @param suggestionCache 建议结果缓存（TTL 30–60 秒，按租户主动失效）
     */
    public MrpSuggestionService(MrpAnalysisService mrpAnalysisService,
                                MrpSuggestionCache suggestionCache) {
        this.mrpAnalysisService = mrpAnalysisService;
        this.suggestionCache = suggestionCache;
    }

    /**
     * 查询采购建议。
     *
     * @param query 查询条件
     * @param pageQuery 分页与排序参数；{@code sort} 不开放
     * @return 按 {@code materialCode} 升序的分页建议
     */
    public PageResult<MrpAnalysisResponse.MrpSuggestionView> suggest(MrpSuggestionQuery query,
                                                                    PageQuery pageQuery) {
        return suggest(query, pageQuery, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 查询采购建议（显式传入当前时间，便于验证窗口边界）。
     *
     * @param query 查询条件
     * @param pageQuery 分页与排序参数；{@code sort} 不开放
     * @param now 当前时间（UTC），窗口上界为 {@code now + horizonDays}
     * @return 按 {@code materialCode} 升序的分页建议
     */
    PageResult<MrpAnalysisResponse.MrpSuggestionView> suggest(MrpSuggestionQuery query, PageQuery pageQuery,
                                                             OffsetDateTime now) {
        if (query == null || query.sandboxId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sandboxId 不能为空");
        }
        int horizonDays = query.horizonDays() == null ? DEFAULT_HORIZON_DAYS : query.horizonDays();
        if (horizonDays < MIN_HORIZON_DAYS || horizonDays > MAX_HORIZON_DAYS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "horizonDays 取值范围 " + MIN_HORIZON_DAYS + "–" + MAX_HORIZON_DAYS);
        }
        PageQuery normalized = pageQuery == null ? new PageQuery() : pageQuery;
        if (normalized.getSort() != null && !normalized.getSort().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 参数不开放，固定按 materialCode ASC");
        }
        normalized.normalize();

        UUID tenantId = TenantContext.getTenantId();
        List<MrpAnalysisResponse.MrpSuggestionView> filtered = suggestionCache.getOrLoad(
                tenantId, query.sandboxId(), horizonDays, query.orderNos(),
                () -> computeSuggestions(query, horizonDays, now));
        int offset = Math.min(normalized.offset(), filtered.size());
        int end = Math.min(offset + normalized.getSize(), filtered.size());
        return PageResult.of(filtered.subList(offset, end), normalized, filtered.size());
    }

    private List<MrpAnalysisResponse.MrpSuggestionView> computeSuggestions(MrpSuggestionQuery query,
                                                                           int horizonDays, OffsetDateTime now) {
        MrpAnalysisResponse analysis = mrpAnalysisService.analyze(
                new MrpAnalysisCommand(query.sandboxId(), query.orderNos(), true, false));
        OffsetDateTime deadline = now.plusDays(horizonDays);
        return analysis.shortages().stream()
                .filter(item -> item.earliestRequiredAt() != null
                        && !item.earliestRequiredAt().isAfter(deadline))
                .sorted(Comparator.comparing(MrpAnalysisResponse.MrpShortage::materialCode))
                .map(MrpAnalysisResponse.MrpSuggestionView::from)
                .toList();
    }
}
