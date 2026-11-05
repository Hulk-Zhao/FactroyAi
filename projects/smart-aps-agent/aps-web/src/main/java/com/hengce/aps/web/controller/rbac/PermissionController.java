package com.hengce.aps.web.controller.rbac;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.rbac.PermissionService;
import com.hengce.aps.service.rbac.PermissionView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限点 API（C30）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/permissions")
@Tag(name = "认证与权限", description = "权限点闭集")
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 构造控制器。
     *
     * @param permissionService 权限服务
     */
    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 列出 V1 的 18 个权限点。
     *
     * @return 权限点
     */
    @Operation(summary = "权限点列表", description = "仅需已认证；返回 V1 的 18 个权限码。")
    @RequiresPermission
    @GetMapping
    public Result<List<PermissionView>> list() {
        return Result.success(permissionService.listAll());
    }
}
