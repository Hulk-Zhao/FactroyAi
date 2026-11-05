package com.hengce.aps.solver.timefold.bench;

import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.OperationDependency;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import com.hengce.aps.solver.api.TaskAssignment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 case.json 的题面规格确定性地物化为引擎中立的 {@link ScheduleProblem}。
 *
 * <p>所有 UUID 由 {@code caseId + 序号} 派生（{@link UUID#nameUUIDFromBytes}），时间基准固定为
 * JSON 中的 {@code anchor}，不读 {@code LocalDateTime.now()}，因此同一份题面在任何机器、
 * 任何时刻物化出的问题完全一致。</p>
 *
 * <p>题面指纹不只看 JSON：{@link #problemDigest(BenchCase, ScheduleProblem)} 还会对物化后的
 * 完整问题（订单、工序、资源、日历窗口、冻结/初值、换型矩阵、基准时间）做规范化 SHA256。
 * 生成器代码一旦改动导致题面变化，指纹同样会变。</p>
 */
final class BenchProblemFactory {

    private static final String MATERIAL_KEY_TYPE = "MATERIAL_CODE";

    private BenchProblemFactory() {
    }

    /**
     * 物化题面。
     *
     * @param benchCase 固化题
     * @return 引擎中立问题
     */
    static ScheduleProblem materialize(BenchCase benchCase) {
        String caseId = benchCase.caseId();
        Map<String, Object> spec = benchCase.spec();
        LocalDateTime anchor = LocalDateTime.parse(Json.str(spec, "anchor", "2026-01-05T00:00:00"));
        int orderCount = Json.intValue(spec, "orders", 3);
        int operationsPerOrder = Json.intValue(spec, "operationsPerOrder", 2);
        int ordersWithoutRouting = Json.intValue(spec, "ordersWithoutRouting", 0);
        int primaryCount = Math.max(1, Json.intValue(spec, "resources", 2));
        int secondaryCount = Json.intValue(spec, "secondaryResources", 0);
        int secondaryWindowHours = Json.intValue(spec, "secondaryWindowHours", 0);
        int secondaryWindowDays = Json.intValue(spec, "secondaryWindowDays", 1);
        int qty = Json.intValue(spec, "qty", 12);
        long standardSeconds = Json.longValue(spec, "standardSeconds", 600L);
        long setupSeconds = Json.longValue(spec, "setupSeconds", 600L);
        List<Integer> dueOffsets = Json.intList(spec, "dueOffsetDays", List.of(1, 2, 3));
        List<String> priorities = Json.stringList(spec, "priorityCycle", List.of("NORMAL"));
        List<String> families = Json.stringList(spec, "familyCycle", List.of("FAM-0", "FAM-1"));
        String calendar = Json.str(spec, "calendar", "NONE");
        int shiftHours = Json.intValue(spec, "shiftHours", 8);
        String setupMatrixMode = Json.str(spec, "setupMatrix", "NONE");
        int frozenCount = Json.intValue(spec, "frozenCount", 0);
        boolean frozenOverlap = Json.bool(spec, "frozenOverlap", false);
        int frozenStartHour = Json.intValue(spec, "frozenStartHourOffset", 8);
        int initialOrders = Json.intValue(spec, "initialOrders", 0);
        int initialStartHour = Json.intValue(spec, "initialStartHourOffset", 8);
        boolean explicitDependencies = Json.bool(spec, "explicitDependencies", false);
        boolean autoSecondary = Json.bool(spec, "allTasksSecondary", true);

        List<SchedulingResource> resources = new ArrayList<>();
        List<UUID> primaryIds = new ArrayList<>();
        Map<UUID, String> resourceCodes = new LinkedHashMap<>();
        List<AvailabilityWindow> primaryWindows = windows(calendar, anchor, shiftHours);
        for (int index = 0; index < primaryCount; index++) {
            UUID resourceId = uuid(caseId, "resource", index);
            String code = "R-" + String.format("%02d", index + 1);
            primaryIds.add(resourceId);
            resourceCodes.put(resourceId, code);
            resources.add(new SchedulingResource(
                    resourceId, code, "PRIMARY", null, "SECONDS_PER_UNIT",
                    BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE", primaryWindows));
        }
        List<UUID> secondaryIds = new ArrayList<>();
        for (int index = 0; index < secondaryCount; index++) {
            UUID resourceId = uuid(caseId, "secondary", index);
            String code = "S-" + String.format("%02d", index + 1);
            secondaryIds.add(resourceId);
            resourceCodes.put(resourceId, code);
            resources.add(new SchedulingResource(
                    resourceId, code, "SECONDARY", null, "SECONDS_PER_UNIT",
                    BigDecimal.ONE, null, BigDecimal.ONE, null, "ACTIVE",
                    secondaryWindows(anchor, secondaryWindowHours, secondaryWindowDays)));
        }

        List<SchedulingOrder> orders = new ArrayList<>();
        List<TaskAssignment> frozen = new ArrayList<>();
        List<TaskAssignment> initial = new ArrayList<>();
        int globalTaskIndex = 0;
        int frozenIndex = 0;
        for (int orderIndex = 0; orderIndex < orderCount; orderIndex++) {
            UUID orderId = uuid(caseId, "order", orderIndex);
            String orderNo = "SO-" + String.format("%03d", orderIndex + 1);
            String family = families.isEmpty() ? null : families.get(orderIndex % families.size());
            String materialCode = "MAT-" + (families.isEmpty() ? 0 : orderIndex % families.size());
            String priority = priorities.isEmpty() ? "NORMAL" : priorities.get(orderIndex % priorities.size());
            LocalDate dueDate = anchor.toLocalDate()
                    .plusDays(dueOffsets.isEmpty() ? 1 : dueOffsets.get(orderIndex % dueOffsets.size()));
            List<SchedulingOperation> operations = new ArrayList<>();
            if (orderIndex >= ordersWithoutRouting) {
                for (int operationIndex = 0; operationIndex < operationsPerOrder; operationIndex++) {
                    int sequence = 10 * (operationIndex + 1);
                    int slot = orderIndex * operationsPerOrder + operationIndex;
                    UUID primaryResourceId = primaryIds.get(slot % primaryCount);
                    List<UUID> secondaries = secondaryIds.isEmpty()
                            ? List.of()
                            : List.of(secondaryIds.get(autoSecondary ? slot % secondaryCount : 0));
                    List<OperationDependency> predecessors = explicitDependencies && operationIndex > 0
                            ? List.of(new OperationDependency(10 * operationIndex, "FS", 0))
                            : List.of();
                    operations.add(new SchedulingOperation(
                            sequence, "OP-" + sequence, "工序 " + sequence, null,
                            BigDecimal.valueOf(standardSeconds), BigDecimal.valueOf(setupSeconds),
                            primaryResourceId, null, secondaries, false, predecessors));
                }
            }
            if (frozenIndex < frozenCount) {
                for (SchedulingOperation operation : operations) {
                    if (frozenIndex >= frozenCount) {
                        break;
                    }
                    int hourOffset = frozenStartHour + (frozenOverlap ? 0 : frozenIndex);
                    frozen.add(assignment(
                            orderId, orderNo, operation, qty, resourceCodes, anchor,
                            hourOffset, "FROZEN", true));
                    frozenIndex++;
                }
            }
            if (orderIndex < initialOrders) {
                for (SchedulingOperation operation : operations) {
                    initial.add(assignment(
                            orderId, orderNo, operation, qty, resourceCodes, anchor,
                            initialStartHour + globalTaskIndex, "PLANNED", false));
                }
            }
            globalTaskIndex += operations.size();
            orders.add(new SchedulingOrder(
                    orderId, orderNo, materialCode, family, BigDecimal.valueOf(qty),
                    dueDate, priority, "UNFROZEN", uuid(caseId, "routing", orderIndex),
                    operations, null));
        }

        List<SetupMatrixEntry> setupMatrix = setupMatrix(setupMatrixMode, families);
        return new ScheduleProblem(
                "tenant-bench", ScheduleProblem.DEFAULT_SANDBOX_ID, null, null, "FORWARD",
                orders, resources, frozen, List.of(), setupMatrix, anchor, initial);
    }

    /**
     * 物化后问题的规范化摘要（题面指纹的一部分）。
     *
     * @param benchCase 固化题
     * @param problem 物化问题
     * @return SHA256
     */
    static String problemDigest(BenchCase benchCase, ScheduleProblem problem) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("caseId", benchCase.caseId());
        canonical.put("anchor", String.valueOf(problem.baseTime()));
        canonical.put("direction", problem.direction());
        List<Object> resourceList = new ArrayList<>();
        for (SchedulingResource resource : problem.resources()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", resource.resourceId().toString());
            item.put("code", resource.code());
            item.put("type", resource.type());
            item.put("capacityMode", resource.capacityMode());
            item.put("capacityValue", resource.capacityValue().toPlainString());
            item.put("efficiency", resource.efficiency().toPlainString());
            item.put("status", resource.status());
            List<Object> windowList = new ArrayList<>();
            for (AvailabilityWindow window : resource.availabilityWindows()) {
                windowList.add(List.of(window.start().toString(), window.end().toString()));
            }
            item.put("windows", windowList);
            resourceList.add(item);
        }
        canonical.put("resources", resourceList);
        List<Object> orderList = new ArrayList<>();
        for (SchedulingOrder order : problem.orders()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", order.orderId().toString());
            item.put("orderNo", order.orderNo());
            item.put("material", order.materialCode());
            item.put("family", order.productFamily());
            item.put("qty", order.qty().toPlainString());
            item.put("dueDate", String.valueOf(order.dueDate()));
            item.put("priority", order.priority());
            List<Object> operationList = new ArrayList<>();
            for (SchedulingOperation operation : order.operations()) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("index", operation.operationIndex());
                op.put("code", operation.operationCode());
                op.put("standardSeconds", operation.standardSeconds().toPlainString());
                op.put("setupSeconds", operation.setupSeconds().toPlainString());
                op.put("primary", String.valueOf(operation.primaryResourceId()));
                op.put("secondary", operation.secondaryResourceIds().stream()
                        .map(UUID::toString).toList());
                op.put("predecessors", operation.predecessors().stream()
                        .map(p -> p.predecessorOperationIndex() + "/" + p.dependType() + "/" + p.lagMinutes())
                        .toList());
                operationList.add(op);
            }
            item.put("operations", operationList);
            orderList.add(item);
        }
        canonical.put("orders", orderList);
        canonical.put("frozen", assignmentsDigest(problem.frozenAssignments()));
        canonical.put("initial", assignmentsDigest(problem.initialAssignments()));
        List<Object> matrixList = new ArrayList<>();
        for (SetupMatrixEntry entry : problem.setupMatrix()) {
            matrixList.add(List.of(
                    String.valueOf(entry.resourceId()),
                    String.valueOf(entry.resourceGroupId()),
                    String.valueOf(entry.keyType()),
                    String.valueOf(entry.fromKey()),
                    String.valueOf(entry.toKey()),
                    entry.setupSeconds()));
        }
        canonical.put("setupMatrix", matrixList);
        return Json.sha256(Json.canonical(canonical));
    }

    /**
     * 按任务数为主、订单数为辅的档位判定口径。
     *
     * <ul>
     *   <li>SMALL：订单 ≤ 3 且任务 ≤ 15</li>
     *   <li>MEDIUM：订单 ≤ 50 且任务 ≤ 350</li>
     *   <li>LARGE：其余（订单 &gt; 50 或任务 &gt; 350）</li>
     * </ul>
     *
     * @param orderCount 订单数
     * @param taskCount 任务数
     * @return SMALL / MEDIUM / LARGE
     */
    static String classify(int orderCount, int taskCount) {
        if (orderCount <= 3 && taskCount <= 15) {
            return "SMALL";
        }
        if (orderCount <= 50 && taskCount <= 350) {
            return "MEDIUM";
        }
        return "LARGE";
    }

    /**
     * 问题任务数（未排位的订单不计）。
     *
     * @param problem 问题
     * @return 任务数
     */
    static int taskCount(ScheduleProblem problem) {
        return problem.orders().stream().mapToInt(order -> order.operations().size()).sum();
    }

    /**
     * 主资源日历窗口：7 天，每天 {@code shiftHours} 小时，起点固定 08:00。
     *
     * <p>8h 班次下每资源每天最多 2 个 2h10 任务块，一周上限 14 个；16h 班次下每天 5 个、
     * 一周上限 35 个。容量题面若按 8h 设计但任务量超过 {@code 资源数 × 14}，应显式声明
     * {@code shiftHours}，而不是把“排不下”写成“预期不可行”。</p>
     *
     * @param calendar 日历类型
     * @param anchor 时间基准
     * @param shiftHours 每天窗口小时数（{@code DAY_SHIFT} 生效）
     * @return 窗口
     */
    private static List<AvailabilityWindow> windows(String calendar, LocalDateTime anchor, int shiftHours) {
        return switch (calendar) {
            case "DAY_SHIFT" -> {
                int hours = Math.max(1, Math.min(24, shiftHours));
                List<AvailabilityWindow> list = new ArrayList<>();
                for (int day = 0; day < 7; day++) {
                    LocalDateTime start = anchor.plusDays(day).plusHours(8);
                    list.add(new AvailabilityWindow(offset(start), offset(start.plusHours(hours))));
                }
                yield list;
            }
            case "SINGLE_WINDOW" -> List.of(new AvailabilityWindow(
                    offset(anchor.plusHours(8)), offset(anchor.plusHours(12))));
            default -> List.of();
        };
    }

    /**
     * 辅助资源窗口：从 anchor 起连续 {@code days} 天，每天 {@code hours} 小时，起点固定 08:00。
     *
     * <p>{@code days=1} 时与修复前完全一致（单日一段），只有显式声明多日的题面才会变化。
     * 单日窗口会让“辅助资源→主资源”的排程被压进同一天，容易产生容量假象（S08），
     * 因此题面可用 {@code secondaryWindowDays} 显式声明多日窗口。</p>
     *
     * @param anchor 时间基准
     * @param hours 每天窗口小时数
     * @param days 天数
     * @return 可用窗口；{@code hours <= 0} 时为空
     */
    private static List<AvailabilityWindow> secondaryWindows(LocalDateTime anchor, int hours, int days) {
        if (hours <= 0) {
            return List.of();
        }
        List<AvailabilityWindow> list = new ArrayList<>();
        for (int day = 0; day < Math.max(1, days); day++) {
            LocalDateTime start = anchor.plusDays(day).plusHours(8);
            list.add(new AvailabilityWindow(offset(start), offset(start.plusHours(hours))));
        }
        return list;
    }

    private static List<SetupMatrixEntry> setupMatrix(String mode, List<String> families) {
        if (!"MATERIAL_CODE".equals(mode)) {
            return List.of();
        }
        List<SetupMatrixEntry> entries = new ArrayList<>();
        entries.add(new SetupMatrixEntry(null, MATERIAL_KEY_TYPE, null, null, 600));
        entries.add(new SetupMatrixEntry(null, MATERIAL_KEY_TYPE, "MAT-0", "MAT-1", 1800));
        entries.add(new SetupMatrixEntry(null, MATERIAL_KEY_TYPE, "MAT-1", "MAT-0", 1800));
        if (families.size() > 2) {
            entries.add(new SetupMatrixEntry(null, MATERIAL_KEY_TYPE, "MAT-0", "MAT-2", 2400));
            entries.add(new SetupMatrixEntry(null, MATERIAL_KEY_TYPE, "MAT-2", "MAT-0", 2400));
        }
        return List.copyOf(entries);
    }

    private static TaskAssignment assignment(
            UUID orderId,
            String orderNo,
            SchedulingOperation operation,
            int qty,
            Map<UUID, String> resourceCodes,
            LocalDateTime anchor,
            int hourOffset,
            String status,
            boolean frozen) {
        long setup = operation.setupSeconds().longValueExact();
        LocalDateTime start = anchor.plusHours(hourOffset);
        LocalDateTime setupEnd = start.plusSeconds(setup);
        LocalDateTime end = setupEnd.plusSeconds(operation.standardSeconds().longValueExact() * qty);
        return new TaskAssignment(
                null,
                orderId,
                orderNo,
                operation.operationIndex(),
                operation.operationName(),
                null,
                operation.primaryResourceId(),
                resourceCodes.get(operation.primaryResourceId()),
                operation.secondaryResourceIds(),
                offset(start),
                offset(setupEnd),
                offset(setupEnd),
                offset(end),
                BigDecimal.valueOf(qty),
                0,
                status,
                frozen,
                1);
    }

    private static List<Object> assignmentsDigest(List<TaskAssignment> assignments) {
        List<Object> list = new ArrayList<>();
        for (TaskAssignment assignment : assignments) {
            list.add(List.of(
                    assignment.orderId().toString(),
                    assignment.operationIndex(),
                    String.valueOf(assignment.resourceId()),
                    String.valueOf(assignment.setupStartTime()),
                    String.valueOf(assignment.endTime())));
        }
        return list;
    }

    private static UUID uuid(String caseId, String kind, int index) {
        return UUID.nameUUIDFromBytes(
                (caseId + "|" + kind + "|" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
