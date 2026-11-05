package com.hengce.aps.web.controller.gantt;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.schedule.ResourceGanttService;
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
 * 资源甘特图 API（contract-freeze C4）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/gantt")
@Tag(name = "甘特图与计划", description = "资源甘特读模型")
public class ResourceGanttController {

    /**
     * 甘特服务。
     */
    private final ResourceGanttService resourceGanttService;

    /**
     * 构造控制器。
     *
     * @param resourceGanttService 甘特服务
     */
    public ResourceGanttController(ResourceGanttService resourceGanttService) {
        this.resourceGanttService = resourceGanttService;
    }

    /**
     * 资源甘特图。
     *
     * @param sandboxId 场景 UUID，缺省 BASELINE
     * @param from 起始时间，ISO-8601 UTC
     * @param to 结束时间，ISO-8601 UTC
     * @param resourceCodes 资源编码过滤，可空
     * @return 按资源分组的甘特数据
     */
    @Operation(summary = "资源甘特图", description = "已实现：返回 [from, to) 内真实 task，含换型窗口。")
    @GetMapping("/resource")
    public Result<ResourceGanttService.GanttView> resourceGantt(
            @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000401") UUID sandboxId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) List<String> resourceCodes) {
        return Result.success(resourceGanttService.query(
                sandboxId, parseTime(from), parseTime(to), resourceCodes));
    }

    private static OffsetDateTime parseTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "时间格式必须为 ISO-8601 且带时区: " + value);
        }
    }
}
