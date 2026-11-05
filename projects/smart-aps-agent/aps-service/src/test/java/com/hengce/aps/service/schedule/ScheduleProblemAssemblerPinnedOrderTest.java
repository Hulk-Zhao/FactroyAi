package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code pinnedOrderNos} 与 {@code orderNos} 交集校验单测。
 */
class ScheduleProblemAssemblerPinnedOrderTest {

    /**
     * 同一订单既参与重排又被 pin：必须 40000 拒绝，message 指名重叠订单。
     */
    @Test
    void overlappingPinnedOrdersShouldBeRejectedWith40000() {
        BizException exception = assertThrows(BizException.class,
                () -> ScheduleProblemAssembler.requireDisjointPinnedOrders(
                        List.of("O-1", "O-2", "O-3"), List.of("O-1", "O-3")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(40000, exception.getErrorCode().getCode());
        assertEquals("pinnedOrderNos 与 orderNos 存在重叠订单: O-1、O-3；钉住的订单不能同时作为重排目标",
                exception.getMessage());
    }

    /**
     * 无重叠：正常返回去重后的 pin 列表，不做任何剔除。
     */
    @Test
    void disjointPinnedOrdersShouldPassThrough() {
        List<String> result = ScheduleProblemAssembler.requireDisjointPinnedOrders(
                List.of("O-2"), List.of("O-1", "O-3", "O-1"));

        assertEquals(List.of("O-1", "O-3"), result);
    }

    /**
     * 未传 pinnedOrderNos：返回空列表，不影响普通求解。
     */
    @Test
    void missingPinnedOrdersShouldReturnEmpty() {
        assertEquals(List.of(), ScheduleProblemAssembler.requireDisjointPinnedOrders(
                List.of("O-1", "O-2"), null));
    }
}
