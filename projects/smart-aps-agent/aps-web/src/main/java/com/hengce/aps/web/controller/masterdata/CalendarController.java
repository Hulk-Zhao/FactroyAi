package com.hengce.aps.web.controller.masterdata;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.masterdata.calendar.CalendarService;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarCreateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarUpdateRequest;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作日历接口：/api/v1/calendars（对齐 C14）。
 *
 * <p>契约占位层已移除日历端点，本控制器为唯一实现。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/calendars")
@Tag(name = "工作日历", description = "工作日历 CRUD（C14）")
public class CalendarController {

    private final CalendarService calendarService;

    /**
     * 构造控制器。
     *
     * @param calendarService 工作日历服务
     */
    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /**
     * 分页查询日历。
     *
     * @param name 名称模糊过滤
     * @param type 类型过滤
     * @param resourceId 资源过滤
     * @param resourceGroupId 资源组过滤
     * @param query 分页参数
     * @return 分页结果
     */
    @GetMapping
    @Operation(summary = "分页查询工作日历")
    public Result<PageResult<CalendarResponse>> list(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) UUID resourceGroupId,
            PageQuery query) {
        return Result.success(calendarService.list(name, type, resourceId, resourceGroupId, query));
    }

    /**
     * 查询日历详情。
     *
     * @param id 日历主键
     * @return 日历详情
     */
    // 排除通用导出/导入保留段，避免 GET /calendars/export 被本方法抢先匹配（C38）
    @GetMapping("/{id:^(?!export$)(?!import$).+}")
    @Operation(summary = "查询工作日历详情")
    public Result<CalendarResponse> get(@PathVariable UUID id) {
        return Result.success(calendarService.get(id));
    }

    /**
     * 新建日历版本。
     *
     * @param request 日历字段
     * @return 新建后的日历
     */
    @PostMapping
    @Operation(summary = "新建工作日历")
    @RequiresPermission("MD_WRITE")
    public Result<CalendarResponse> create(@Valid @RequestBody CalendarCreateRequest request) {
        return Result.success(calendarService.create(request));
    }

    /**
     * 更新日历；历史日历只读，calendarVersion 不可修改。
     *
     * @param id 日历主键
     * @param request 更新字段与当前 version
     * @return 更新后的日历
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新工作日历")
    @RequiresPermission("MD_WRITE")
    public Result<CalendarResponse> update(@PathVariable UUID id,
                                           @Valid @RequestBody CalendarUpdateRequest request) {
        return Result.success(calendarService.update(id, request));
    }

    /**
     * 逻辑删除日历。
     *
     * @param id 日历主键
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除工作日历")
    @RequiresPermission("MD_WRITE")
    public Result<Void> delete(@PathVariable UUID id) {
        calendarService.delete(id);
        return Result.success();
    }
}
