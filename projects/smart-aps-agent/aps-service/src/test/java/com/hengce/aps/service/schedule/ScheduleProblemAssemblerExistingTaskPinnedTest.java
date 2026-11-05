package com.hengce.aps.service.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingEdgeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingNodeMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.OperationTemplateMapper;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.CompletedFrozenOperationRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.FrozenTaskRow;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 装载口径回归：本次请求订单之外的场景既有任务必须以不可移动的 pinned 项进入题面。
 *
 * <p>缺陷现场：只排单张订单时 {@code foundOrders} 只返回请求订单，{@code findFrozenTasks}
 * 只返回 FROZEN/锁任务，普通 PLANNED/IN_PROGRESS 的既有任务全部缺席，求解器以为资源空闲，
 * 落库时被全表重叠校验拦截。修复后 {@code findOrdersWithExistingTasks} 读取的订单必须
 * 出现在 {@code problem.orders()} 里，其任务以 {@code frozen=true} 的既有分配进入题面，
 * 但写入范围 {@code expectedWriteTaskCount()} 仍只含请求订单。</p>
 */
class ScheduleProblemAssemblerExistingTaskPinnedTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID SANDBOX_ID = UUID.randomUUID();
    private static final UUID REQUESTED_ORDER_ID = UUID.randomUUID();
    private static final UUID PINNED_ORDER_ID = UUID.randomUUID();
    private static final UUID RESOURCE_ID = UUID.randomUUID();
    private static final UUID ROUTING_ID = UUID.randomUUID();
    private static final OffsetDateTime TASK_START = OffsetDateTime.parse("2026-11-05T00:00:00Z");

    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    private final RoutingMapper routingMapper = mock(RoutingMapper.class);
    private final RoutingNodeMapper routingNodeMapper = mock(RoutingNodeMapper.class);
    private final RoutingEdgeMapper routingEdgeMapper = mock(RoutingEdgeMapper.class);
    private final OperationTemplateMapper operationTemplateMapper = mock(OperationTemplateMapper.class);
    private final ResourceMapper resourceMapper = mock(ResourceMapper.class);
    private final ResourceGroupMapper resourceGroupMapper = mock(ResourceGroupMapper.class);
    private final CalendarMapper calendarMapper = mock(CalendarMapper.class);

    private final ScheduleProblemAssembler assembler = new ScheduleProblemAssembler(
            scheduleRepository, routingMapper, routingNodeMapper, routingEdgeMapper,
            operationTemplateMapper, resourceMapper, resourceGroupMapper, calendarMapper,
            null, "Asia/Shanghai", null);

    @BeforeEach
    void bindTenant() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("请求订单之外的既有任务订单以 pinned 进入题面，写入范围仍只含请求订单")
    void existingTasksOutsideRequestScopeMustBePinnedIntoProblem() {
        Routing routing = new Routing();
        routing.setId(ROUTING_ID);
        when(routingMapper.selectById(ROUTING_ID)).thenReturn(routing);
        when(routingNodeMapper.selectByRoutingId(ROUTING_ID)).thenReturn(twoNodes());
        when(routingEdgeMapper.selectByRoutingId(ROUTING_ID)).thenReturn(List.of());

        Resource resource = new Resource();
        resource.setId(RESOURCE_ID);
        resource.setCode("RES-01");
        resource.setType(ResourceType.PRIMARY);
        when(resourceMapper.selectList(any())).thenReturn(List.of(resource));
        when(calendarMapper.selectPage(any(), any(), any(), any(), any())).thenReturn(new java.util.ArrayList<Calendar>());

        OrderSourceRow requested = row(REQUESTED_ORDER_ID, "SO-REQ");
        OrderSourceRow pinned = row(PINNED_ORDER_ID, "SO-PIN");
        when(scheduleRepository.findOrders(TENANT_ID, List.of("SO-REQ"), false))
                .thenReturn(List.of(requested));
        when(scheduleRepository.findOrdersWithExistingTasks(
                any(), any(), anyList(), any(), any())).thenReturn(List.of(pinned));
        when(scheduleRepository.findTasksByOrders(TENANT_ID, SANDBOX_ID, List.of(PINNED_ORDER_ID)))
                .thenReturn(List.of(existingTask()));
        when(scheduleRepository.findFrozenTasks(eq(TENANT_ID), eq(SANDBOX_ID),
                any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(List.of());

        SolveRequest request = new SolveRequest("T-1", SANDBOX_ID, null, "timefold",
                List.of("SO-REQ"), Map.of());
        ScheduleProblemAssembler.AssembledProblem assembled =
                assembler.assemble(request, config());

        ScheduleProblem problem = assembled.problem();
        assertThat(problem.orders()).extracting(order -> order.orderNo())
                .containsExactly("SO-REQ", "SO-PIN");
        assertThat(problem.frozenAssignments()).hasSize(1);
        TaskAssignment pinnedAssignment = problem.frozenAssignments().get(0);
        assertThat(pinnedAssignment.taskId()).isEqualTo(1L);
        assertThat(pinnedAssignment.orderNo()).isEqualTo("SO-PIN");
        assertThat(pinnedAssignment.frozen()).as("既有占用必须是不可移动的 pinned 项").isTrue();
        assertThat(assembled.expectedTaskCount()).as("8 个请求工序 + 8 个既有工序").isEqualTo(16);
        assertThat(assembled.expectedWriteTaskCount()).as("只写请求订单").isEqualTo(8);
        assertThat(assembled.orderIds()).containsExactly(REQUESTED_ORDER_ID);
    }

    @Test
    @DisplayName("窗口外已完成 FROZEN 工序移出题面，剩余工序带订单级硬下界")
    void completedFrozenOperationsOutsideWindowBecomeOrderLowerBound() {
        stubSingleRequestedOrder();
        OffsetDateTime maxEnd = TASK_START.plusHours(4);
        when(scheduleRepository.findFrozenOperationsBeforeWindow(
                eq(TENANT_ID), eq(SANDBOX_ID), any(OffsetDateTime.class)))
                .thenReturn(List.of(
                        new CompletedFrozenOperationRow(REQUESTED_ORDER_ID, 1, TASK_START.plusHours(1)),
                        new CompletedFrozenOperationRow(REQUESTED_ORDER_ID, 2, TASK_START.plusHours(2)),
                        new CompletedFrozenOperationRow(REQUESTED_ORDER_ID, 3, maxEnd)));

        ScheduleProblemAssembler.AssembledProblem assembled = assembler.assemble(
                new SolveRequest("T-2", SANDBOX_ID, null, "timefold", List.of("SO-REQ"), Map.of()),
                config());

        ScheduleProblem problem = assembled.problem();
        assertThat(problem.orders()).hasSize(1);
        assertThat(problem.orders().get(0).operations())
                .as("工序 1-3 已在窗口外完工，必须移出题面")
                .extracting(operation -> operation.operationIndex())
                .containsExactly(4, 5, 6, 7, 8);
        assertThat(problem.orders().get(0).notBefore())
                .as("剩余工序不得早于窗口外 FROZEN 任务的最大结束时间")
                .isEqualTo(maxEnd.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        assertThat(assembled.expectedTaskCount()).isEqualTo(5);
        assertThat(assembled.expectedWriteTaskCount()).isEqualTo(5);
        assertThat(assembled.orderIds()).containsExactly(REQUESTED_ORDER_ID);
    }

    private void stubSingleRequestedOrder() {
        Routing routing = new Routing();
        routing.setId(ROUTING_ID);
        when(routingMapper.selectById(ROUTING_ID)).thenReturn(routing);
        when(routingNodeMapper.selectByRoutingId(ROUTING_ID)).thenReturn(twoNodes());
        when(routingEdgeMapper.selectByRoutingId(ROUTING_ID)).thenReturn(List.of());

        Resource resource = new Resource();
        resource.setId(RESOURCE_ID);
        resource.setCode("RES-01");
        resource.setType(ResourceType.PRIMARY);
        when(resourceMapper.selectList(any())).thenReturn(List.of(resource));
        when(calendarMapper.selectPage(any(), any(), any(), any(), any()))
                .thenReturn(new java.util.ArrayList<Calendar>());

        when(scheduleRepository.findOrders(TENANT_ID, List.of("SO-REQ"), false))
                .thenReturn(List.of(row(REQUESTED_ORDER_ID, "SO-REQ")));
        when(scheduleRepository.findOrdersWithExistingTasks(
                any(), any(), anyList(), any(), any())).thenReturn(List.of());
        when(scheduleRepository.findFrozenTasks(eq(TENANT_ID), eq(SANDBOX_ID),
                any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(List.of());
    }

    private SolveConfig config() {
        return new SolveConfig("timefold", 10, 0, "LATE_ACCEPTANCE", 0, 0, 10,
                "FORWARD", true, Map.of());
    }

    private OrderSourceRow row(UUID orderId, String orderNo) {
        return new OrderSourceRow(orderId, orderNo, UUID.randomUUID(), "M-1", null,
                BigDecimal.TEN, LocalDate.of(2026, 11, 20), "NORMAL", "UNFROZEN",
                ROUTING_ID, "CONFIRMED");
    }

    private List<RoutingNode> twoNodes() {
        List<RoutingNode> nodes = new java.util.ArrayList<>();
        for (int seq = 1; seq <= 8; seq++) {
            RoutingNode node = new RoutingNode();
            node.setId(UUID.randomUUID());
            node.setSeqNo(seq);
            node.setNodeKey("N" + seq);
            node.setOperationCode("OP" + seq);
            node.setOperationName("工序" + seq);
            node.setStandardSeconds(60);
            node.setSetupSeconds(0);
            node.setIsOptional(false);
            nodes.add(node);
        }
        return nodes;
    }

    private FrozenTaskRow existingTask() {
        OffsetDateTime end = TASK_START.plusHours(1);
        return new FrozenTaskRow(1L, PINNED_ORDER_ID, "SO-PIN", 1, null, RESOURCE_ID, "RES-01",
                List.of(), TASK_START, TASK_START, TASK_START, end, BigDecimal.TEN,
                "PLANNED", 1, null, null, null, List.of(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
