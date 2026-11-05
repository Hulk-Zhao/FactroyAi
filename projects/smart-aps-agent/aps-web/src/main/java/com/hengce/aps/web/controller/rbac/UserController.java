package com.hengce.aps.web.controller.rbac;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.rbac.UserCreateCommand;
import com.hengce.aps.service.rbac.UserService;
import com.hengce.aps.service.rbac.UserStatus;
import com.hengce.aps.service.rbac.UserUpdateCommand;
import com.hengce.aps.service.rbac.UserView;
import com.hengce.aps.web.dto.rbac.UserCreateRequest;
import com.hengce.aps.web.dto.rbac.UserUpdateRequest;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理 API（C30 / 25.1.4）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/users")
@Tag(name = "认证与权限", description = "用户管理")
public class UserController {

    private final UserService userService;

    /**
     * 构造控制器。
     *
     * @param userService 用户服务
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户分页列表。
     *
     * @param query 分页参数
     * @param status 状态过滤
     * @return 分页用户
     */
    @Operation(summary = "用户列表", description = "需要 SYS_USER 权限。")
    @RequiresPermission("SYS_USER")
    @GetMapping
    public Result<PageResult<UserView>> list(@ParameterObject PageQuery query,
                                             @Parameter(description = "状态") @RequestParam(required = false)
                                             UserStatus status) {
        return Result.success(userService.list(query, status));
    }

    /**
     * 创建用户。
     *
     * @param request 创建请求
     * @return 新用户
     */
    @Operation(summary = "创建用户", description = "需要 SYS_USER 权限；口令 bcrypt 落库。")
    @RequiresPermission("SYS_USER")
    @PostMapping
    public Result<UserView> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.success(userService.create(new UserCreateCommand(request.username(),
                request.displayName(), request.email(), request.phone(), request.password(),
                request.roleCodes())));
    }

    /**
     * 修改用户。
     *
     * @param userId 用户主键
     * @param request 修改请求
     * @return 修改后的用户
     */
    @Operation(summary = "修改用户", description = "需要 SYS_USER；version 不匹配 40901；禁止停用自己/最后一个 ADMIN。")
    @RequiresPermission("SYS_USER")
    @PatchMapping("/{userId}")
    public Result<UserView> update(@PathVariable UUID userId, @Valid @RequestBody UserUpdateRequest request) {
        return Result.success(userService.update(userId, new UserUpdateCommand(request.version(),
                request.displayName(), request.email(), request.phone(), request.status(),
                request.roleCodes(), request.password())));
    }
}
