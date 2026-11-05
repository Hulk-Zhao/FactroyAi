package com.hengce.aps.web.controller.rbac;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.audit.AuditLogService;
import com.hengce.aps.service.audit.AuditLogView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志 API（C31 / 25.2.3），只读。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/audit-logs")
@Tag(name = "审计日志", description = "只读查询，敏感字段已脱敏")
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 构造控制器。
     *
     * @param auditLogService 审计服务
     */
    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 审计日志分页查询。
     *
     * @param query 分页参数
     * @param action 动作
     * @param resourceType 资源类型
     * @param resourceId 资源业务标识
     * @param userId 操作人
     * @param from 起始时间（含）
     * @param to 结束时间（不含）
     * @param sort 仅 createdAt,asc|desc
     * @return 分页审计记录
     */
    @Operation(summary = "审计日志", description = "需要 SYS_AUDIT；过滤条件 AND；时间区间 [from, to)。")
    @RequiresPermission("SYS_AUDIT")
    @GetMapping
    public Result<PageResult<AuditLogView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "事件类型") @RequestParam(required = false) String action,
            @Parameter(description = "资源类型") @RequestParam(required = false) String resourceType,
            @Parameter(description = "资源业务标识") @RequestParam(required = false) String resourceId,
            @Parameter(description = "操作人") @RequestParam(required = false) UUID userId,
            @Parameter(description = "起始时间 ISO-8601") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "结束时间 ISO-8601") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "createdAt,asc|desc") @RequestParam(required = false) String sort) {
        return Result.success(auditLogService.query(query, action, resourceType, resourceId, userId,
                from, to, sort));
    }
}
