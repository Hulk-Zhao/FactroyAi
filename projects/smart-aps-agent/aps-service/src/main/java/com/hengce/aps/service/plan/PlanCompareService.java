package com.hengce.aps.service.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanCompareRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanCompareRepository.SolveSnapshot;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import com.hengce.aps.service.plan.PlanCompareView.PlanCompareItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 多策略择优对比（C35 §25.7.2）：只读、不落库、不产生新计划版本。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>{@code planIds} 先去重再计数，必须为 2–3 个；缺失或数量越界均为 40000，计划不存在/无快照 42200；</li>
 *   <li>版本必须有快照，否则 42200；</li>
 *   <li>评分与 metrics 来自产生该发布快照的最后一次 SOLVED 求解记录（同租户、同场景、
 *       发布时刻之前，按 {@code finished_at DESC, id DESC} 取唯一一条）；</li>
 *   <li>排序固定 {@code scoreHard desc → scoreSoft desc → planId asc}，{@code recommendedPlanId}
 *       恒为排序第一项，同输入多次调用结果一致；</li>
 *   <li>{@code metrics} 原样透传，不重命名、不裁剪业务键；仅剔除 §24.2 已冻结不再输出的
 *       {@code stepCount}/{@code unimprovedStepCount}。</li>
 * </ul>
 */
@Service
public class PlanCompareService {

    /**
     * 冻结的并列处理规则。
     */
    public static final String TIE_BREAK = PlanCompareView.TIE_BREAK;

    private static final int MIN_PLAN_COUNT = 2;

    private static final int MAX_PLAN_COUNT = 3;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * §24.2 起不再输出的计数键；即使历史 solve_task.metrics 仍含这两个键也不得透出。
     */
    private static final Set<String> REMOVED_METRIC_KEYS = Set.of("stepCount", "unimprovedStepCount");

    private static final Comparator<PlanCompareItem> RECOMMENDATION_ORDER = Comparator
            .comparing(PlanCompareItem::scoreHard, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PlanCompareItem::scoreSoft, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(item -> item.planId().toString());

    private final PlanVersionRepository planVersionRepository;

    private final PlanCompareRepository planCompareRepository;

    /**
     * 构造服务。
     *
     * @param planVersionRepository 计划版本持久层
     * @param planCompareRepository 择优只读持久层
     */
    public PlanCompareService(PlanVersionRepository planVersionRepository,
                              PlanCompareRepository planCompareRepository) {
        this.planVersionRepository = planVersionRepository;
        this.planCompareRepository = planCompareRepository;
    }

    /**
     * 对比 2–3 个计划版本并给出确定性推荐。
     *
     * @param planIds 计划版本主键，允许重复输入，服务端先去重
     * @return 已排序的对比结果
     */
    public PlanCompareView compare(List<UUID> planIds) {
        List<UUID> unique = normalizePlanIds(planIds);
        UUID tenantId = TenantContext.getTenantId();
        List<PlanCompareItem> items = new ArrayList<>();
        for (UUID planId : unique) {
            PlanVersionRow row = planVersionRepository.find(tenantId, planId)
                    .orElseThrow(() -> new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "计划不存在: " + planId));
            if (!hasSnapshot(row.snapshotJson())) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "计划版本无快照，无法对比: " + planId);
            }
            SolveSnapshot solve = planCompareRepository
                    .findLatestSolvedBefore(tenantId, row.sandboxId(), row.publishedAt())
                    .orElseThrow(() -> new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "计划版本缺少可用的已求解记录: " + planId));
            items.add(new PlanCompareItem(planId, row.planNo(), row.versionNo(),
                    solve.scoreHard(), solve.scoreSoft(), row.taskCount(),
                    metricsOf(solve.metricsJson())));
        }
        items.sort(RECOMMENDATION_ORDER);
        UUID recommended = items.isEmpty() ? null : items.get(0).planId();
        return new PlanCompareView(List.copyOf(items), recommended, TIE_BREAK);
    }

    /**
     * 去重并校验数量：缺失 40000；空元素/去重后不足 2 或超过 3 返回 42200。
     *
     * @param planIds 原始输入
     * @return 去重后的主键列表（保持输入顺序）
     */
    private static List<UUID> normalizePlanIds(List<UUID> planIds) {
        if (planIds == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "planIds 不能为空");
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID planId : planIds) {
            if (planId == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "planIds 不能包含空元素");
            }
            unique.add(planId);
        }
        if (unique.size() < MIN_PLAN_COUNT || unique.size() > MAX_PLAN_COUNT) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "planIds 去重后必须为 2–3 个，实际为 " + unique.size());
        }
        return new ArrayList<>(unique);
    }

    /**
     * 判断版本是否已固化快照；发布时快照不会为空，默认 {@code {}} 视为无快照。
     *
     * @param snapshotJson 快照文本
     * @return {@code true} 表示有快照
     */
    private static boolean hasSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return false;
        }
        String trimmed = snapshotJson.trim();
        return !"{}".equals(trimmed) && !"null".equals(trimmed);
    }

    /**
     * 解析 metrics 并剔除已冻结不再输出的计数键，其余键原样保留。
     *
     * @param metricsJson solve_task.metrics JSON 原文
     * @return 透传用 metrics
     */
    private static Map<String, Object> metricsOf(String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metrics = OBJECT_MAPPER.readValue(metricsJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            if (metrics == null) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>(metrics);
            REMOVED_METRIC_KEYS.forEach(result::remove);
            return result;
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "solve_task.metrics 不是合法 JSON 对象，无法对比");
        }
    }
}
