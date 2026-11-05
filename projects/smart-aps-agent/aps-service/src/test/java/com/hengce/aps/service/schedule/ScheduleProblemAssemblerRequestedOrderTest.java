package com.hengce.aps.service.schedule;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.OrderSourceRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 请求订单存在性校验：点名的订单号查不到时必须 400，而不是静默漏排后照样 SOLVED。
 */
class ScheduleProblemAssemblerRequestedOrderTest {

    /**
     * 含 1 个不存在的订单号 → {@code 400/40000}，message 逐字点名。
     */
    @Test
    void missingOrderNoShouldBeRejectedWithExactMessage() {
        List<OrderSourceRow> found = List.of(row("O-1", "CONFIRMED"));
        BizException exception = assertThrows(BizException.class,
                () -> ScheduleProblemAssembler.requireAllRequestedOrdersFound(
                        List.of("O-1", "O-99", "O-100"), found));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(40000, exception.getErrorCode().getCode());
        assertEquals("请求的订单号不存在: O-99、O-100", exception.getMessage());
    }

    /**
     * 全部合法（哪怕状态是 CANCELLED）→ 不抛异常：存在性判定不掺状态语义。
     */
    @Test
    void allRequestedOrderNosFoundShouldPass() {
        List<OrderSourceRow> found = List.of(
                row("O-1", "CONFIRMED"), row("O-2", "CONFIRMED"), row("O-3", "CANCELLED"));

        assertDoesNotThrow(() -> ScheduleProblemAssembler.requireAllRequestedOrdersFound(
                List.of("O-1", "O-2", "O-3"), found));
    }

    /**
     * “不在该场景/沙箱范围内”这一维在本系统不存在：{@code orders} 表没有 {@code sandbox_id}，
     * 订单按租户隔离（见 {@code ScheduleRepository#findOrders} 的 SQL：只有 {@code tenant_id} 与
     * {@code deleted=false} 过滤）。因此订单只要在租户内存在，就通过存在性校验，
     * 场景范围只约束 {@code task}/{@code plan_version}，不约束订单本身。
     */
    @Test
    void orderFromAnotherSandboxStillPassesBecauseOrdersAreTenantScoped() {
        List<OrderSourceRow> found = List.of(row("O-4", "CONFIRMED"));

        assertDoesNotThrow(() -> ScheduleProblemAssembler.requireAllRequestedOrdersFound(
                List.of("O-4"), found));
    }

    /**
     * 空请求（走全量模式）不做存在性校验。
     */
    @Test
    void emptyRequestShouldSkipExistenceCheck() {
        assertDoesNotThrow(() -> ScheduleProblemAssembler.requireAllRequestedOrdersFound(
                List.of(), List.of()));
    }

    /**
     * 显式点名的订单已终态取消 → {@code 400/40000}，message 逐字点名。
     */
    @Test
    void cancelledOrderNoShouldBeRejectedWithExactMessage() {
        List<OrderSourceRow> found = List.of(
                row("O-1", "CONFIRMED"), row("O-3", "CANCELLED"));
        BizException exception = assertThrows(BizException.class,
                () -> ScheduleProblemAssembler.requireNoCancelledOrderNos(
                        List.of("O-1", "O-3"), found));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(40000, exception.getErrorCode().getCode());
        assertEquals("请求的订单号已取消，不能排产: O-3", exception.getMessage());
    }

    /**
     * 混合输入（1 个不存在 + 1 个已取消）：受理入口先跑存在性校验，所以对外只报“不存在”那一类。
     */
    @Test
    void mixedMissingAndCancelledReportsMissingFirst() {
        List<OrderSourceRow> found = List.of(
                row("O-1", "CONFIRMED"), row("O-3", "CANCELLED"));
        List<String> requested = List.of("O-1", "O-99", "O-3");

        BizException existence = assertThrows(BizException.class,
                () -> ScheduleProblemAssembler.requireAllRequestedOrdersFound(requested, found));
        assertEquals("请求的订单号不存在: O-99", existence.getMessage());

        BizException cancelled = assertThrows(BizException.class,
                () -> ScheduleProblemAssembler.requireNoCancelledOrderNos(requested, found));
        assertEquals("请求的订单号已取消，不能排产: O-3", cancelled.getMessage());
    }

    /**
     * 除 CANCELLED 外的状态仍照排：状态前置校验只拦终态取消。
     */
    @Test
    void nonCancelledStatusesShouldPassStatusCheck() {
        List<OrderSourceRow> found = List.of(
                row("O-5", "DRAFT"), row("O-6", "PLANNED"),
                row("O-7", "IN_PROGRESS"), row("O-8", "COMPLETED"));

        assertDoesNotThrow(() -> ScheduleProblemAssembler.requireNoCancelledOrderNos(
                List.of("O-5", "O-6", "O-7", "O-8"), found));
    }

    private static OrderSourceRow row(String orderNo, String status) {
        return new OrderSourceRow(
                UUID.randomUUID(), orderNo, UUID.randomUUID(), "FG-200", "FAM-A",
                BigDecimal.TEN, LocalDate.of(2026, 11, 4), "NORMAL", "UNFROZEN", null, status);
    }
}
