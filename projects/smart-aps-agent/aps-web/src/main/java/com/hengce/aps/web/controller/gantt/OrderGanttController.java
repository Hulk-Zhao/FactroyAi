package com.hengce.aps.web.controller.gantt;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.schedule.OrderGanttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单甘特图 API（C29）：订单泳道 + 任务条 + 交期线 + 晚交标识。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/gantt")
@Tag(name = "甘特图与计划", description = "订单甘特读模型")
public class OrderGanttController {

    /**
     * 订单甘特服务。
     */
    private final OrderGanttService orderGanttService;

    /**
     * 构造控制器。
     *
     * @param orderGanttService 订单甘特服务
     */
    public OrderGanttController(OrderGanttService orderGanttService) {
        this.orderGanttService = orderGanttService;
    }

    /**
     * 订单甘特图。
     *
     * @param sandboxId 场景 UUID，缺省 BASELINE
     * @param from 起始时间，ISO-8601 UTC
     * @param to 结束时间，ISO-8601 UTC
     * @param orderNos 订单号过滤，可空
     * @return 按订单分组的甘特数据
     */
    @Operation(summary = "订单甘特图", description = "已实现：返回订单泳道、窗口内任务条、交期线与晚交标识。")
    @GetMapping("/order")
    public Result<OrderGanttService.OrderGanttView> orderGantt(
            @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000401") UUID sandboxId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> orderNos) {
        return Result.success(orderGanttService.query(
                sandboxId, parseTime(from), parseTime(to), orderNos));
    }

    private static OffsetDateTime parseTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时间格式必须为 ISO-8601 且带时区: " + value);
        }
    }
}
