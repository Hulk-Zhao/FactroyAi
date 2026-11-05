package com.hengce.aps.service.sandbox;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.plan.PlanVersionRepository;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository.CompareTaskRow;
import com.hengce.aps.infrastructure.persistence.sandbox.SandboxRepository.SandboxRow;
import com.hengce.aps.service.audit.AuditValues;
import com.hengce.aps.service.plan.PlanActionView;
import com.hengce.aps.service.plan.PlanVersionService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场景生命周期服务（C32 §25.3）。
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>创建只允许 {@code WHAT_IF}，并深拷贝父场景 {@code snapshot}；</li>
 *   <li>{@code apply} 在单事务内完成「新建计划版本 → C24 发布/回填 → 旧 BASELINE 归档 →
 *       目标提升 BASELINE」，任一步失败整体回滚，不留半截 {@code plan_version}；</li>
 *   <li>「每租户唯一 BASELINE」由 V1 部分唯一索引 {@code uq_sandbox_baseline} 兜底；</li>
 *   <li>{@code compare} 方向固定 {@code id - targetId}，计数字段全量、明细最多 200 条。</li>
 * </ul>
 */
@Service
public class SandboxService {

    private static final DateTimeFormatter PLAN_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String TYPE_BASELINE = "BASELINE";

    private static final String TYPE_WHAT_IF = "WHAT_IF";

    private static final String TYPE_ARCHIVED = "ARCHIVED";

    private final SandboxRepository sandboxRepository;

    private final PlanVersionRepository planVersionRepository;

    private final PlanVersionService planVersionService;

    private final AuditLogRepository auditLogRepository;

    private final ZoneId businessZone;

    /**
     * 构造服务。
     *
     * @param sandboxRepository 场景持久层
     * @param planVersionRepository 计划版本持久层
     * @param planVersionService C24 发布服务
     * @param auditLogRepository 审计持久层
     * @param businessTimeZone 业务时区（{@code aps.business.time-zone}），用于 compare 的交期截止点
     */
    public SandboxService(SandboxRepository sandboxRepository,
                          PlanVersionRepository planVersionRepository,
                          PlanVersionService planVersionService,
                          AuditLogRepository auditLogRepository,
                          @Value("${aps.business.time-zone:Asia/Shanghai}") String businessTimeZone) {
        this.sandboxRepository = sandboxRepository;
        this.planVersionRepository = planVersionRepository;
        this.planVersionService = planVersionService;
        this.auditLogRepository = auditLogRepository;
        this.businessZone = ZoneId.of(businessTimeZone.trim());
    }

    /**
     * 创建 WHAT_IF 场景：深拷贝父场景 snapshot，父场景缺省为当前 BASELINE。
     *
     * @param name 名称，1–128
     * @param type 类型，缺省 WHAT_IF；仅允许 WHAT_IF
     * @param parentSandboxId 父场景，可空
     * @param expiresAt 过期时间，可空
     * @return 场景摘要
     */
    @Transactional
    public SandboxSummaryView create(String name, String type, UUID parentSandboxId, OffsetDateTime expiresAt) {
        UUID tenantId = TenantContext.getTenantId();
        String effectiveType = type == null || type.isBlank() ? TYPE_WHAT_IF : type.trim().toUpperCase();
        if (!TYPE_WHAT_IF.equals(effectiveType)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "只允许创建 WHAT_IF 场景；BASELINE 由 apply 产生，ARCHIVED 不可直接创建");
        }
        SandboxRow parent = resolveParent(tenantId, parentSandboxId);
        UUID operator = AuditContext.getCurrentUserId();
        UUID id = sandboxRepository.insertWhatIf(tenantId, name.trim(), parent.id(),
                parent.snapshotJson(), expiresAt, operator);
        return toSummary(requireSandbox(tenantId, id));
    }

    /**
     * 分页查询场景；默认排除 ARCHIVED，排序固定 createdAt。
     *
     * @param query 分页参数，可为空
     * @param type 类型过滤，可空
     * @return 分页摘要
     */
    public PageResult<SandboxSummaryView> list(PageQuery query, String type) {
        PageQuery normalized = query == null ? new PageQuery() : query.normalize();
        UUID tenantId = TenantContext.getTenantId();
        String filter = normalizeTypeFilter(type);
        boolean ascending = parseSort(normalized.getSort());
        long total = sandboxRepository.count(tenantId, filter);
        List<SandboxSummaryView> items = sandboxRepository
                .page(tenantId, filter, ascending, normalized.offset(), normalized.getSize())
                .stream().map(SandboxService::toSummary).toList();
        return PageResult.of(items, normalized, total);
    }

    /**
     * 应用 WHAT_IF 场景为新的 BASELINE。
     *
     * <p>步骤严格按契约顺序：先发布新计划版本并回填任务，再归档旧 BASELINE、提升目标；
     * 任一步抛异常时整个事务回滚。</p>
     *
     * @param sandboxId 目标场景
     * @param reason 可选说明
     * @return 场景摘要 + 计划动作
     */
    @Transactional
    public SandboxApplyResultView apply(UUID sandboxId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        SandboxRow target = sandboxRepository.findForUpdate(tenantId, sandboxId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId));
        if (TYPE_BASELINE.equals(target.type())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "场景已应用为 BASELINE，不可重复应用");
        }
        if (TYPE_ARCHIVED.equals(target.type())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "已归档场景不可应用");
        }
        if (!TYPE_WHAT_IF.equals(target.type())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅 WHAT_IF 场景可应用");
        }
        long taskCount = sandboxRepository.countTasks(tenantId, sandboxId);
        if (taskCount == 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "场景内无任务，不可应用");
        }

        UUID operator = AuditContext.getCurrentUserId();
        int versionNo = planVersionRepository.nextVersionNo(tenantId, sandboxId);
        String planNo = planVersionRepository.nextPlanNo(tenantId,
                LocalDate.now().format(PLAN_DATE));
        UUID planId = planVersionRepository.insertDraftVersion(tenantId, sandboxId, planNo, versionNo, operator);
        PlanActionView published = planVersionService.publish(planId, reason, null);

        SandboxRow oldBaseline = sandboxRepository.findBaseline(tenantId)
                .orElseThrow(() -> new BizException(ErrorCode.STATE_CONFLICT,
                        "租户缺少 BASELINE 场景，无法完成应用"));
        int archived = sandboxRepository.archiveBaseline(tenantId, oldBaseline.id(), operator);
        if (archived != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "旧 BASELINE 归档失败");
        }
        int promoted = sandboxRepository.promoteToBaseline(tenantId, sandboxId, operator);
        if (promoted != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "目标场景提升 BASELINE 失败");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("planId", published.planId().toString());
        after.put("planVersion", published.version());
        after.put("archivedSandboxId", oldBaseline.id().toString());
        auditLogRepository.record(tenantId, "SANDBOX_APPLIED", "SANDBOX", sandboxId.toString(), null,
                AuditValues.redact(after));

        return new SandboxApplyResultView(toSummary(requireSandbox(tenantId, sandboxId)), published);
    }

    /**
     * 对比两个场景，方向为 {@code id - targetId}。
     *
     * @param sandboxId 被比较方
     * @param targetId 基线方
     * @return 对比结果
     */
    public SandboxCompareView compare(UUID sandboxId, UUID targetId) {
        if (sandboxId.equals(targetId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "id 与 targetId 不能相同");
        }
        UUID tenantId = TenantContext.getTenantId();
        requireSandbox(tenantId, sandboxId);
        requireSandbox(tenantId, targetId);
        List<CompareTaskRow> comparedTasks = sandboxRepository.listCompareTasks(tenantId, sandboxId);
        List<CompareTaskRow> targetTasks = sandboxRepository.listCompareTasks(tenantId, targetId);
        return SandboxComparisonCalculator.compare(sandboxId, targetId, comparedTasks, targetTasks, businessZone);
    }

    /**
     * 当前生效的业务时区（供单测校验配置绑定，不对外暴露）。
     *
     * @return 业务时区
     */
    ZoneId businessTimeZone() {
        return businessZone;
    }

    private SandboxRow resolveParent(UUID tenantId, UUID parentSandboxId) {
        Optional<SandboxRow> parent = parentSandboxId == null
                ? sandboxRepository.findBaseline(tenantId)
                : sandboxRepository.find(tenantId, parentSandboxId);
        SandboxRow row = parent.orElseThrow(() -> new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                "父场景不存在: " + (parentSandboxId == null ? "当前租户无 BASELINE" : parentSandboxId)));
        if (TYPE_ARCHIVED.equals(row.type())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "已归档场景不可作为父场景");
        }
        return row;
    }

    private SandboxRow requireSandbox(UUID tenantId, UUID sandboxId) {
        return sandboxRepository.find(tenantId, sandboxId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId));
    }

    private static String normalizeTypeFilter(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase();
        if (!TYPE_BASELINE.equals(normalized)
                && !TYPE_WHAT_IF.equals(normalized)
                && !TYPE_ARCHIVED.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 仅允许 BASELINE / WHAT_IF / ARCHIVED");
        }
        return normalized;
    }

    private static boolean parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return false;
        }
        String normalized = sort.trim().toLowerCase().replace(":", ",").replace(" ", ",");
        String[] parts = normalized.split(",");
        if (parts.length > 2 || !"createdat".equals(parts[0])) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 仅允许 createdAt");
        }
        return parts.length == 2 && "asc".equals(parts[1]);
    }

    private static SandboxSummaryView toSummary(SandboxRow row) {
        return new SandboxSummaryView(row.id(), row.name(), row.type(), row.parentSandboxId(),
                row.expiresAt(), row.version(), row.createdAt());
    }
}
