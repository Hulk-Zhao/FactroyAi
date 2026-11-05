package com.hengce.aps.web.controller.rbac;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.rbac.MeView;
import com.hengce.aps.service.rbac.UserService;
import com.hengce.aps.web.security.ApsSecurityContext;
import com.hengce.aps.web.security.AuthPrincipal;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户 API（C30）。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX)
@Tag(name = "认证与权限", description = "当前用户信息")
public class MeController {

    /**
     * off 模式下本地 demo 的默认管理员（V1 种子）。
     */
    private static final UUID DEMO_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final UserService userService;

    /**
     * 构造控制器。
     *
     * @param userService 用户服务
     */
    public MeController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 当前用户信息与权限并集。
     *
     * @return 用户 + 角色 + 权限
     */
    @Operation(summary = "当前用户", description = "返回角色与权限并集；off 模式回落 V1 管理员，便于本地 demo。")
    @RequiresPermission
    @GetMapping("/me")
    public Result<MeView> me() {
        UUID userId = resolveUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return Result.success(userService.me(userId));
    }

    private UUID resolveUserId() {
        AuthPrincipal principal = ApsSecurityContext.getPrincipal();
        if (principal != null) {
            return principal.userId();
        }
        return ApsSecurityContext.isOff() ? DEMO_ADMIN_ID : null;
    }
}
