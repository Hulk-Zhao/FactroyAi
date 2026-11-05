package com.hengce.aps.service.schedule.infeasibility;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReasonCode;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 修复候选生成器（给 C52 供料）。
 *
 * <p><b>本轮不做反事实验证</b>：只输出“改什么可能可行”的结构，候选参数全部取自本次
 * 题面真实字段（资源主键、可用窗口边界、冻结任务主键），不编造资源编码或日期。</p>
 */
public final class RepairAdvisor {

    /**
     * 候选排序权重：解冻代价最低。
     */
    private static final int RANK_UNFREEZE = 1;

    /**
     * 候选排序权重：延长日历。
     */
    private static final int RANK_EXTEND = 2;

    private RepairAdvisor() {
    }

    /**
     * 生成修复候选。
     *
     * @param bundle 证据集合
     * @param problem 题面；用于判断资源是否真的配置了日历窗口
     * @return 候选列表（未验证），按 rank 升序
     */
    public static List<RepairCandidate> advise(EvidenceBundle bundle, ScheduleProblem problem) {
        List<RepairCandidate> candidates = new ArrayList<>();
        addUnfreezeCandidate(bundle, candidates);
        addExtendCandidates(bundle, problem, candidates);
        candidates.sort(Comparator.comparingInt(RepairCandidate::rank));
        return List.copyOf(candidates);
    }

    private static void addUnfreezeCandidate(EvidenceBundle bundle, List<RepairCandidate> candidates) {
        Set<Long> taskIds = new LinkedHashSet<>();
        for (InfeasibilityReport.BlockingTask task : bundle.blockingTasks()) {
            if (task.reasonCode() == InfeasibilityReasonCode.FROZEN_CONFLICT && task.taskId() != null) {
                taskIds.add(task.taskId());
            }
        }
        if (taskIds.isEmpty()) {
            return;
        }
        candidates.add(new RepairCandidate(RepairKind.UNFREEZE_TASKS,
                InfeasibilityReasonCode.FROZEN_CONFLICT,
                Map.of("taskIds", List.copyOf(taskIds)),
                List.copyOf(taskIds),
                "冻结/锁定任务之间存在不可移动的资源重叠；解冻 " + taskIds.size()
                        + " 个任务后可由求解器重新分配时段",
                RANK_UNFREEZE));
    }

    private static void addExtendCandidates(EvidenceBundle bundle, ScheduleProblem problem,
                                            List<RepairCandidate> candidates) {
        Map<UUID, SchedulingResource> resourceById = new LinkedHashMap<>();
        for (SchedulingResource resource : problem.resources()) {
            resourceById.put(resource.resourceId(), resource);
        }
        Set<UUID> added = new LinkedHashSet<>();
        for (InfeasibilityReport.ResourceWindow window : bundle.resourceWindows()) {
            if (!added.add(window.resourceId())) {
                continue;
            }
            SchedulingResource resource = resourceById.get(window.resourceId());
            if (resource == null || resource.availabilityWindows().isEmpty()) {
                continue;
            }
            AvailabilityWindow last = resource.availabilityWindows()
                    .get(resource.availabilityWindows().size() - 1);
            OffsetDateTime from = last.end();
            OffsetDateTime to = from.plusSeconds(extensionSeconds(window.gapHours()));
            candidates.add(new RepairCandidate(RepairKind.EXTEND_CALENDAR,
                    InfeasibilityReasonCode.RESOURCE_WINDOW_SHORT,
                    Map.of("resourceId", resource.resourceId(), "from", from, "to", to),
                    List.of(),
                    "资源 " + resource.code() + " 缺口 " + window.gapHours().toPlainString()
                            + "h；把日历从 " + from + " 延长到 " + to + " 后重解可验证",
                    RANK_EXTEND));
        }
    }

    private static long extensionSeconds(BigDecimal gapHours) {
        BigDecimal safe = gapHours == null ? BigDecimal.ZERO : gapHours;
        long hours = safe.setScale(0, RoundingMode.CEILING).longValueExact();
        return Math.max(hours, 1L) * 3600L;
    }
}
