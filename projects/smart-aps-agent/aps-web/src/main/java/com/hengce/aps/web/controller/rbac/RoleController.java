package com.hengce.aps.web.controller.rbac;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.rbac.RoleCreateCommand;
import com.hengce.aps.service.rbac.RoleListResponse;
import com.hengce.aps.service.rbac.RoleService;
import com.hengce.aps.service.rbac.RoleView;
import com.hengce.aps.web.dto.rbac.RoleCreateRequest;
import com.hengce.aps.web.dto.rbac.RolePermissionsRequest;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理 API（C30 / 25.1.5）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/roles")
@Tag(name = "认证与权限", description = "角色与授权")
public class RoleController {

    private final RoleService roleService;

    /**
     * 构造控制器。
     *
     * @param roleService 角色服务
     */
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * 角色列表。
     *
     * @return 角色及权限
     */
    @Operation(summary = "角色列表", description = "契约要求 SYS_USER 权限。")
    @RequiresPermission("SYS_USER")
    @GetMapping
    public Result<RoleListResponse> list() {
        return Result.success(new RoleListResponse(roleService.list()));
    }

    /**
     * 创建角色。
     *
     * @param request 创建请求
     * @return 新角色
     */
    @Operation(summary = "创建角色", description = "需要 SYS_ROLE；权限码必须是 V1 的 18 个闭集。")
    @RequiresPermission("SYS_ROLE")
    @PostMapping
    public Result<RoleView> create(@Valid @RequestBody RoleCreateRequest request) {
        return Result.success(roleService.create(new RoleCreateCommand(request.code(), request.name(),
                request.description(), request.permissionCodes())));
    }

    /**
     * 全量替换角色权限。
     *
     * @param roleId 角色主键
     * @param request 权限码
     * @return 更新后的角色
     */
    @Operation(summary = "替换角色权限", description = "需要 SYS_ROLE；全量替换并落审计。")
    @RequiresPermission("SYS_ROLE")
    @PutMapping("/{roleId}/permissions")
    public Result<RoleView> replacePermissions(@PathVariable UUID roleId,
                                               @Valid @RequestBody RolePermissionsRequest request) {
        return Result.success(roleService.replacePermissions(roleId, request.permissionCodes()));
    }
}
