package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.GanttTaskRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 资源甘特读模型服务：按 {@code sandbox + resource + 时间} 查询任务并分组。
 *
 * <p>查询谓词固定为 {@code sandbox_id = ? AND start_time < ? AND end_time > ?}，
 * 指定资源时追加 {@code resource_id IN (...)}，命中 {@code idx_task_sandbox_resource_start}。</p>
 */
@Service
public class ResourceGanttService {

    /**
     * 排产持久层。
     */
    private final ScheduleRepository scheduleRepository;

    /**
     * 构造甘特服务。
     *
     * @param scheduleRepository 排产持久层
     */
    public ResourceGanttService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * 查询资源甘特。
     *
     * @param sandboxId 场景标识，可空则取默认场景
     * @param from 起始时间（含），UTC
     * @param to 结束时间（不含），UTC
     * @param resourceCodes 资源编码过滤，可空
     * @return 按资源分组的甘特视图
     */
    public GanttView query(UUID sandboxId, OffsetDateTime from, OffsetDateTime to, List<String> resourceCodes) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "from/to 不能为空");
        }
        OffsetDateTime start = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = to.withOffsetSameInstant(ZoneOffset.UTC);
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "to 必须晚于 from");
        }
        UUID scene = sandboxId == null ? com.hengce.aps.solver.api.ScheduleProblem.DEFAULT_SANDBOX_ID : sandboxId;
        UUID tenantId = TenantContext.getTenantId();
        List<String> codes = resourceCodes == null ? List.of()
                : resourceCodes.stream().filter(code -> !code.isBlank()).toList();
        List<UUID> resourceIds = scheduleRepository.findResourceIdsByCodes(tenantId, codes);
        if (!codes.isEmpty() && resourceIds.isEmpty()) {
            return new GanttView(scene, start, end, List.of());
        }
        Map<UUID, String> codeById = scheduleRepository.findResourceCodes(tenantId);
        Map<String, UUID> calendarIdByCode = scheduleRepository.findResourceCalendarIds(tenantId);
        List<GanttTaskRow> rows = scheduleRepository.findGanttTasks(tenantId, scene, resourceIds, start, end);
        Map<String, List<GanttTaskView>> grouped = new LinkedHashMap<>();
        Map<String, GanttTaskRow> headByResource = new LinkedHashMap<>();
        for (GanttTaskRow row : rows) {
            String code = row.resourceCode() == null ? "UNASSIGNED" : row.resourceCode();
            headByResource.putIfAbsent(code, row);
            List<String> secondaryCodes = new ArrayList<>();
            for (UUID secondaryId : row.secondaryResourceIds()) {
                String secondaryCode = codeById.get(secondaryId);
                if (secondaryCode != null) {
                    secondaryCodes.add(secondaryCode);
                }
            }
            grouped.computeIfAbsent(code, key -> new ArrayList<>()).add(new GanttTaskView(
                    row.id(),
                    row.orderNo(),
                    row.operationIndex(),
                    row.operationName(),
                    row.resourceCode(),
                    List.copyOf(secondaryCodes),
                    row.startTime(),
                    row.endTime(),
                    row.setupStartTime(),
                    row.setupEndTime(),
                    row.qty(),
                    row.status(),
                    row.sequenceInResource(),
                    row.version()));
        }
        List<GanttResourceRow> resources = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<GanttTaskView>> entry : grouped.entrySet()) {
            GanttTaskRow head = headByResource.get(entry.getKey());
            UUID calendarId = calendarIdByCode.get(entry.getKey());
            resources.add(new GanttResourceRow(
                    head.resourceCode(),
                    head.resourceName(),
                    head.resourceType(),
                    calendarId,
                    calendarId != null,
                    List.copyOf(entry.getValue())));
        }
        resources.sort(Comparator.comparing(row -> row.resourceCode() == null ? "" : row.resourceCode()));
        return new GanttView(scene, start, end, List.copyOf(resources));
    }

    /**
     * 资源甘特视图。
     *
     * @param sandboxId 场景标识
     * @param from 起始时间
     * @param to 结束时间
     * @param resources 按资源分组的数据
     */
    public record GanttView(UUID sandboxId, OffsetDateTime from, OffsetDateTime to,
                            List<GanttResourceRow> resources) {
    }

    /**
     * 资源甘特行。
     *
     * <p>全局 Jackson 配置为 {@code non_null}，这里显式 {@code ALWAYS}：未绑定日历的资源
     * 必须返回 {@code "calendarId": null}，不能省略字段（C4 契约）。</p>
     *
     * @param resourceCode 资源编码
     * @param resourceName 资源名称
     * @param resourceType 资源类型
     * @param calendarId 资源绑定的日历标识；未绑定为 {@code null}（不填默认日历）
     * @param calendarBound 是否已绑定日历，便于前端免二次查询
     * @param tasks 资源内任务
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GanttResourceRow(String resourceCode, String resourceName, String resourceType,
                                   UUID calendarId, boolean calendarBound,
                                   List<GanttTaskView> tasks) {
    }

    /**
     * 甘特任务。
     *
     * @param id 任务主键
     * @param orderNo 订单号
     * @param operationIndex 工序序号
     * @param operationName 工序名称
     * @param resourceCode 主资源编码
     * @param secondaryResourceCodes 辅助资源编码
     * @param startTime 加工开始
     * @param endTime 加工结束
     * @param setupStartTime 换型开始
     * @param setupEndTime 换型结束
     * @param qty 数量
     * @param status 任务状态
     * @param sequenceInResource 资源内顺序
     * @param version 版本
     */
    public record GanttTaskView(long id, String orderNo, int operationIndex, String operationName,
                                String resourceCode, List<String> secondaryResourceCodes,
                                OffsetDateTime startTime, OffsetDateTime endTime,
                                OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                                BigDecimal qty, String status, Integer sequenceInResource, int version) {
    }
}
