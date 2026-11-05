package com.hengce.aps.web.controller.rbac;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.service.rbac.AuthenticatedUser;
import com.hengce.aps.service.rbac.AuthService;
import com.hengce.aps.web.dto.rbac.LoginRequest;
import com.hengce.aps.web.dto.rbac.LoginResult;
import com.hengce.aps.web.security.JwtTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录 API（C30）。不提供 {@code /auth/refresh}，8 小时到期重新登录。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/auth")
@Tag(name = "认证与权限", description = "登录签发 JWT")
public class AuthController {

    /**
     * JWT 中必须包含的访问角色。
     */
    private static final String ACCESS_ROLE = "APS_USER";

    private final AuthService authService;

    private final JwtTokenService jwtTokenService;

    /**
     * 构造控制器。
     *
     * @param authService 登录服务
     * @param jwtTokenService JWT 服务
     */
    public AuthController(AuthService authService, JwtTokenService jwtTokenService) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * 登录并签发 JWT。
     *
     * @param request 登录请求
     * @return 令牌与有效期
     */
    @Operation(summary = "登录", description = "bcrypt 校验；5 次失败锁 15 分钟；返回 HS256 JWT（TTL 28800s）。")
    @PostMapping("/login")
    public Result<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedUser user = authService.login(request.username(), request.password());
        List<String> roles = new ArrayList<>(new LinkedHashSet<>(user.roleCodes()));
        if (!roles.contains(ACCESS_ROLE)) {
            roles.add(ACCESS_ROLE);
        }
        String token = jwtTokenService.issue(user.userId(), user.tenantId(), roles);
        return Result.success(new LoginResult(token, "Bearer", jwtTokenService.ttlSeconds(), user.userId()));
    }
}
