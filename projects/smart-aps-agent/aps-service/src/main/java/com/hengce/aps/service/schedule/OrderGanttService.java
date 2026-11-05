package com.hengce.aps.service.schedule;

import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderGanttRow;
import com.hengce.aps.solver.api.ScheduleProblem;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 订单甘特读模型服务（C29）：按订单泳道返回任务条、交期线与晚交标识。
 *
 * <p>窗口内无任务的订单仍返回空泳道，便于前端绘制交期线；任务仅取
 * {@code start_time < to AND end_time > from} 的重叠部分。交期线取交期日
 * 23:59:59（UTC），任务结束时间晚于该时刻即判定晚交。</p>
 */
@Service
public class OrderGanttService {

    /**
     * 排产持久层。
     */
    private final ScheduleRepository scheduleRepository;

    /**
     * 构造订单甘特服务。
     *
     * @param scheduleRepository 排产持久层
     */
    public OrderGanttService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * 查询订单甘特。
     *
     * @param sandboxId 场景标识，可空则取默认场景
     * @param from 起始时间（含），UTC
     * @param to 结束时间（不含），UTC
     * @param orderNos 订单号过滤，可空
     * @return 按订单分组的甘特视图
     */
    public OrderGanttView query(UUID sandboxId, OffsetDateTime from, OffsetDateTime to, List<String> orderNos) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "from/to 不能为空");
        }
        OffsetDateTime start = from.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end = to.withOffsetSameInstant(ZoneOffset.UTC);
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "to 必须晚于 from");
        }
        UUID scene = sandboxId == null ? ScheduleProblem.DEFAULT_SANDBOX_ID : sandboxId;
        UUID tenantId = TenantContext.getTenantId();
        List<String> filters = orderNos == null ? List.of()
                : orderNos.stream().filter(no -> !no.isBlank()).toList();
        List<OrderGanttRow> rows = scheduleRepository.findOrderGanttRows(tenantId, scene, filters, start, end);
        Map<UUID, String> codeById = scheduleRepository.findResourceCodes(tenantId);
        Map<UUID, LaneBuilder> lanes = new LinkedHashMap<>();
        for (OrderGanttRow row : rows) {
            OffsetDateTime dueDateLine = dueDateLine(row.dueDate());
            LaneBuilder lane = lanes.get(row.orderId());
            if (lane == null) {
                lane = new LaneBuilder(row, dueDateLine);
                lanes.put(row.orderId(), lane);
            }
            if (row.taskId() == null || row.startTime() == null || row.endTime() == null) {
                continue;
            }
            boolean late = isLate(row.endTime(), dueDateLine);
            List<String> secondaryCodes = new ArrayList<>();
            for (UUID secondaryId : row.secondaryResourceIds()) {
                String code = codeById.get(secondaryId);
                if (code != null) {
                    secondaryCodes.add(code);
                }
            }
            lane.addTask(new OrderTaskView(
                    row.taskId(),
                    row.orderNo(),
                    row.operationIndex() == null ? 0 : row.operationIndex(),
                    row.operationName(),
                    row.resourceCode(),
                    List.copyOf(secondaryCodes),
                    row.startTime(),
                    row.endTime(),
                    row.setupStartTime(),
                    row.setupEndTime(),
                    row.qty(),
                    row.taskStatus(),
                    row.sequenceInResource(),
                    row.version() == null ? 0 : row.version(),
                    lockType(row.taskStatus()),
                    late));
        }
        List<OrderLane> orders = new ArrayList<>(lanes.size());
        for (LaneBuilder lane : lanes.values()) {
            OrderLane built = lane.build();
            if (built.tasks().isEmpty() && !dueDateInWindow(built.dueDate(), start, end)) {
                continue;
            }
            orders.add(built);
        }
        return new OrderGanttView(scene, start, end, List.copyOf(orders));
    }

    /**
     * 交期线：交期日 23:59:59（UTC）；交期缺失时返回 null，不参与晚交判定。
     *
     * @param dueDate 交期日
     * @return 交期线，可空
     */
    public static OffsetDateTime dueDateLine(LocalDate dueDate) {
        return dueDate == null ? null
                : dueDate.atTime(23, 59, 59).atOffset(ZoneOffset.UTC);
    }

    private static boolean isLate(OffsetDateTime endTime, OffsetDateTime dueDateLine) {
        return dueDateLine != null && endTime.isAfter(dueDateLine);
    }

    /**
     * 任务锁类型快照（C29）：M2 先由 {@code task.status} 推导 FROZEN，其余为 NONE；
     * V5 生效后由 {@code lock_type} 完整回读 TIME_WINDOW/RESOURCE。
     *
     * @param taskStatus 任务状态
     * @return 锁类型
     */
    private static String lockType(String taskStatus) {
        return "FROZEN".equals(taskStatus) ? "FROZEN" : "NONE";
    }

    /**
     * 无任务的订单仅在交期日落入 [from, to) 日期区间时保留泳道，避免返回窗口外空泳道。
     *
     * @param dueDate 交期日
     * @param from 窗口开始
     * @param to 窗口结束（不含）
     * @return 是否保留
     */
    private static boolean dueDateInWindow(LocalDate dueDate, OffsetDateTime from, OffsetDateTime to) {
        return dueDate != null
                && !dueDate.isBefore(from.toLocalDate())
                && dueDate.isBefore(to.toLocalDate());
    }

    /**
     * 订单甘特视图。
     *
     * @param sandboxId 场景标识
     * @param from 起始时间
     * @param to 结束时间
     * @param orders 订单泳道
     */
    public record OrderGanttView(UUID sandboxId, OffsetDateTime from, OffsetDateTime to,
                                 List<OrderLane> orders) {
    }

    /**
     * 订单泳道。
     *
     * @param orderId 订单主键（任务跳转锚点）
     * @param orderNo 订单号
     * @param materialCode 产品物料编码
     * @param materialName 产品物料名称（C50），可空
     * @param materialSpec 产品物料规格（C50），可空
     * @param productFamily 产品族（物料分类编码），可空
     * @param dueDate 交期日
     * @param dueDateLine 交期线（交期日 23:59:59 UTC），可空
     * @param priority 优先级
     * @param freezeStatus 冻结状态
     * @param orderStatus 订单状态
     * @param late 是否存在晚交任务
     * @param lateMinutes 最大晚交分钟数，仅 {@code late=true} 时非空
     * @param lateTaskCount 晚交任务数
     * @param tasks 任务条
     */
    public record OrderLane(UUID orderId, String orderNo, String materialCode, String materialName,
                            String materialSpec, String productFamily,
                            LocalDate dueDate, OffsetDateTime dueDateLine, String priority,
                            String freezeStatus, String orderStatus, boolean late, Long lateMinutes,
                            int lateTaskCount, List<OrderTaskView> tasks) {
    }

    /**
     * 订单任务条。
     *
     * @param id 任务主键（任务跳转锚点）
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
     * @param lockType 任务锁类型（NONE/TIME_WINDOW/RESOURCE/FROZEN）
     * @param late 是否晚交
     */
    public record OrderTaskView(long id, String orderNo, int operationIndex, String operationName,
                                String resourceCode, List<String> secondaryResourceCodes,
                                OffsetDateTime startTime, OffsetDateTime endTime,
                                OffsetDateTime setupStartTime, OffsetDateTime setupEndTime,
                                BigDecimal qty, String status, Integer sequenceInResource, int version,
                                String lockType, boolean late) {
    }

    /**
     * 单个订单泳道的构建器。
     */
    private static final class LaneBuilder {

        private final UUID orderId;
        private final String orderNo;
        private final String materialCode;
        private final String materialName;
        private final String materialSpec;
        private final String productFamily;
        private final LocalDate dueDate;
        private final OffsetDateTime dueDateLine;
        private final String priority;
        private final String freezeStatus;
        private final String orderStatus;
        private final List<OrderTaskView> tasks = new ArrayList<>();

        private LaneBuilder(OrderGanttRow row, OffsetDateTime dueDateLine) {
            this.orderId = row.orderId();
            this.orderNo = row.orderNo();
            this.materialCode = row.materialCode();
            this.materialName = row.materialName();
            this.materialSpec = row.materialSpec();
            this.productFamily = row.productFamily();
            this.dueDate = row.dueDate();
            this.dueDateLine = dueDateLine;
            this.priority = row.priority();
            this.freezeStatus = row.freezeStatus();
            this.orderStatus = row.orderStatus();
        }

        private void addTask(OrderTaskView task) {
            tasks.add(task);
        }

        private OrderLane build() {
            int lateCount = 0;
            OffsetDateTime latestLateEnd = null;
            for (OrderTaskView task : tasks) {
                if (task.late()) {
                    lateCount++;
                    if (latestLateEnd == null || task.endTime().isAfter(latestLateEnd)) {
                        latestLateEnd = task.endTime();
                    }
                }
            }
            Long lateMinutes = latestLateEnd == null ? null
                    : Duration.between(dueDateLine, latestLateEnd).toMinutes();
            return new OrderLane(orderId, orderNo, materialCode, materialName, materialSpec,
                    productFamily, dueDate, dueDateLine,
                    priority, freezeStatus, orderStatus, lateCount > 0, lateMinutes, lateCount,
                    List.copyOf(tasks));
        }
    }
}
