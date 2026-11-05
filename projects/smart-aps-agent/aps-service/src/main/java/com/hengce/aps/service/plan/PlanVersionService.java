package com.hengce.aps.service.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository.PlanVersionRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划版本发布/回滚服务（C24）。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>发布把 DRAFT/REVIEW 版本置为 PUBLISHED，快照只写一次，后续调用幂等返回；</li>
 *   <li>回滚不改历史版本、不物理覆盖快照，而是新建 PUBLISHED 版本并复制目标快照；</li>
 *   <li>场景内存在 IN_PROGRESS/COMPLETED 任务时拒绝回滚，返回 40903。</li>
 * </ul>
 */
@Service
public class PlanVersionService {

    /**
     * 快照序列化器；快照内时间已转为 ISO-8601 字符串，避免 Java 8 时间模块依赖。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final DateTimeFormatter PLAN_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final PlanVersionRepository planVersionRepository;

    /**
     * 构造服务。
     *
     * @param planVersionRepository 计划版本持久层
     */
    public PlanVersionService(PlanVersionRepository planVersionRepository) {
        this.planVersionRepository = planVersionRepository;
    }

    /**
     * 发布计划版本：固化不可变快照并回填任务版本号；重复发布幂等。
     *
     * @param planId 计划版本主键
     * @param reason 发布原因，可为空
     * @param approver 审批人，可为空；为空时落 NULL，不使用机器标识冒充审批人
     * @return 计划动作响应
     */
    @Transactional
    public PlanActionView publish(UUID planId, String reason, String approver) {
        UUID tenantId = TenantContext.getTenantId();
        PlanVersionRow row = requireVersion(tenantId, planId);
        if (STATUS_PUBLISHED.equals(row.status())) {
            return toAction(row, null, 0);
        }
        if (STATUS_ARCHIVED.equals(row.status())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "已归档的计划版本不可发布");
        }
        // 硬防护：增量求解异步且完成时会重建任务，未终态期间发布会把旧任务固化进正式版本。
        List<PlanVersionRepository.ActiveSolveTask> activeSolveTasks =
                planVersionRepository.findActiveSolveTasks(tenantId, row.sandboxId());
        if (!activeSolveTasks.isEmpty()) {
            String running = String.join("、", activeSolveTasks.stream()
                    .map(task -> "solveTaskId=" + task.solveTaskId() + "/" + task.status())
                    .toList());
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "场景内存在未完成的求解任务（" + running + "），发布被拒绝；请等待求解结束后再发布");
        }
        List<String> scopeOrderNos = readScopeOrderNos(row.snapshotJson());
        List<Map<String, Object>> tasks = planVersionRepository
                .listTasksForSnapshot(tenantId, row.sandboxId(), scopeOrderNos);
        String snapshot = toJson(buildSnapshot(row, tasks));
        UUID operator = AuditContext.getCurrentUserId();
        String effectiveApprover = trimToNull(approver);
        int updated = planVersionRepository.publish(tenantId, row, snapshot, tasks.size(),
                operator, effectiveApprover, trimToNull(reason));
        if (updated == 0) {
            PlanVersionRow latest = requireVersion(tenantId, planId);
            if (STATUS_PUBLISHED.equals(latest.status())) {
                return toAction(latest, null, 0);
            }
            throw new BizException(ErrorCode.STATE_CONFLICT, "计划版本状态已变化，请刷新后重试");
        }
        int affected = planVersionRepository.backfillTasks(tenantId, row.sandboxId(), planId,
                scopeOrderNos);
        PlanVersionRow published = requireVersion(tenantId, planId);
        return toAction(published, null, affected);
    }

    /**
     * 回滚到指定版本：新建 PUBLISHED 版本，复制目标快照，并把来源版本任务指向新版本。
     *
     * @param planId 当前计划版本主键
     * @param targetVersionNo 目标版本号
     * @param reason 回滚原因，可为空
     * @return 计划动作响应
     */
    @Transactional
    public PlanActionView rollback(UUID planId, int targetVersionNo, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        PlanVersionRow source = requireVersion(tenantId, planId);
        int executed = planVersionRepository.countExecutedTasks(tenantId, source.sandboxId());
        if (executed > 0) {
            throw new BizException(ErrorCode.PUBLISHED_CONFLICT,
                    "存在已执行任务（IN_PROGRESS/COMPLETED）共 " + executed + " 条，禁止回滚");
        }
        PlanVersionRow target = planVersionRepository
                .findPublishedByVersionNo(tenantId, source.sandboxId(), targetVersionNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "目标版本不存在或未发布: " + targetVersionNo));
        if (target.id().equals(source.id())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标版本与当前版本相同");
        }
        UUID newId = planVersionRepository.insertRollbackVersion(
                tenantId, source.sandboxId(),
                planVersionRepository.nextPlanNo(tenantId, LocalDate.now().format(PLAN_DATE)),
                planVersionRepository.nextVersionNo(tenantId, source.sandboxId()),
                target.snapshotJson() == null ? "{}" : target.snapshotJson(),
                target.taskCount(), AuditContext.getCurrentUserId(), source.id(), trimToNull(reason));
        int affected = planVersionRepository.repointTasks(tenantId, source.sandboxId(), source.id(), newId);
        PlanVersionRow created = requireVersion(tenantId, newId);
        return toAction(created, targetVersionNo, affected);
    }

    /**
     * 查询计划版本详情与快照任务。
     *
     * @param planId 计划版本主键
     * @return 版本详情
     */
    public PlanDetailView detail(UUID planId) {
        UUID tenantId = TenantContext.getTenantId();
        PlanVersionRow row = requireVersion(tenantId, planId);
        return new PlanDetailView(toAction(row, null, 0), readSnapshotTasks(row.snapshotJson()));
    }

    /**
     * 分页查询场景内计划版本轨迹，不含任务明细。
     *
     * @param query 分页参数，可为空
     * @param sandboxId 场景标识
     * @return 分页版本摘要
     */
    public PageResult<PlanVersionSummaryView> list(PageQuery query, UUID sandboxId) {
        PageQuery normalized = query == null ? new PageQuery() : query.normalize();
        UUID tenantId = TenantContext.getTenantId();
        long total = planVersionRepository.countBySandbox(tenantId, sandboxId);
        List<PlanVersionRow> rows = planVersionRepository.pageBySandbox(
                tenantId, sandboxId, normalized.offset(), normalized.getSize());
        List<PlanVersionSummaryView> items = rows.stream().map(PlanVersionService::toSummary).toList();
        return PageResult.of(items, normalized, total);
    }

    private PlanVersionRow requireVersion(UUID tenantId, UUID planId) {
        return planVersionRepository.find(tenantId, planId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "计划版本不存在: " + planId));
    }

    private static Map<String, Object> buildSnapshot(PlanVersionRow row, List<Map<String, Object>> tasks) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("capturedAt", Instant.now().toString());
        snapshot.put("planId", row.id().toString());
        snapshot.put("sandboxId", row.sandboxId().toString());
        snapshot.put("taskCount", tasks.size());
        snapshot.put("tasks", tasks);
        return snapshot;
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("计划快照序列化失败", exception);
        }
    }

    /**
     * 读取 DRAFT 快照里的求解范围；无范围标记时返回空列表，表示按场景全量发布。
     */
    @SuppressWarnings("unchecked")
    private static List<String> readScopeOrderNos(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(snapshotJson, Map.class);
            Object scope = snapshot.get("scopeOrderNos");
            if (!(scope instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(value -> value instanceof String)
                    .map(value -> (String) value).toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("计划快照解析失败", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readSnapshotTasks(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(snapshotJson, Map.class);
            Object tasks = snapshot.get("tasks");
            return tasks instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("计划快照解析失败", exception);
        }
    }

    private static PlanVersionSummaryView toSummary(PlanVersionRow row) {
        return new PlanVersionSummaryView(row.id(), row.planNo(), row.versionNo(), row.status(),
                row.publishedAt(), row.publishedBy(), row.approver(), row.taskCount(), row.rollbackOf());
    }

    private static PlanActionView toAction(PlanVersionRow row, Integer targetVersionNo,
                                           int affectedTaskCount) {
        return new PlanActionView(row.id(), row.planNo(), row.versionNo(), targetVersionNo,
                row.status(), row.publishedAt(), affectedTaskCount, row.sandboxId(), row.taskCount(),
                row.approver(), row.reason(), row.rollbackOf());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
