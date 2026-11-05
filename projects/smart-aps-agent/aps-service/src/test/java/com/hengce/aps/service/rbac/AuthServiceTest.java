package com.hengce.aps.service.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.UserMapper;
import com.hengce.aps.infrastructure.persistence.rbac.RbacRepository;
import com.hengce.aps.infrastructure.persistence.rbac.UserEntity;
import com.hengce.aps.service.audit.AuditEventType;
import com.hengce.aps.service.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * C30 登录成功、密码错误、连续失败锁定与解锁测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-04T08:00:00Z");

    @Mock
    private UserMapper userMapper;

    @Mock
    private RbacRepository rbacRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        authService = new AuthService(userMapper, rbacRepository, auditLogService, passwordEncoder, clock,
                new MockEnvironment());
        TenantContext.clear();
    }

    @Test
    @DisplayName("登录成功：状态 ACTIVE、清空失败计数、写 AUTH_LOGIN")
    void loginSuccess() {
        UserEntity user = user("admin", UserStatus.ACTIVE, "bcrypt-hash");
        user.getExtAttrs().put("loginFailureCount", 2);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("pwd", "bcrypt-hash")).thenReturn(true);
        when(rbacRepository.findRoleCodesByUserId(TENANT_ID, USER_ID)).thenReturn(List.of(RoleCodes.ADMIN));

        AuthenticatedUser result = authService.login("admin", "pwd");

        assertEquals(USER_ID, result.userId());
        assertEquals(List.of(RoleCodes.ADMIN), result.roleCodes());
        assertTrue(!user.getExtAttrs().containsKey("loginFailureCount"));
        assertEquals(UserStatus.ACTIVE.name(), user.getStatus());
        assertEquals(OffsetDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)), user.getLastLoginAt());
        verify(userMapper).updateById(user);
        verify(auditLogService).recordForUser(eq(USER_ID), eq(AuditEventType.AUTH_LOGIN), eq("admin"),
                isNull(), isNull());
    }

    @Test
    @DisplayName("密码错误：40100，失败计数 +1，写 AUTH_LOGIN_FAILED")
    void loginWrongPassword() {
        UserEntity user = user("admin", UserStatus.ACTIVE, "bcrypt-hash");
        user.getExtAttrs().put("loginFailureCount", 0);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("bad", "bcrypt-hash")).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> authService.login("admin", "bad"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertEquals(1, user.getExtAttrs().get("loginFailureCount"));
        assertEquals(UserStatus.ACTIVE.name(), user.getStatus());
        verify(auditLogService).recordLoginFailed(eq(TENANT_ID), eq(USER_ID), eq("admin"));
    }

    @Test
    @DisplayName("第 5 次失败：status=LOCKED 且 locked_until=now+15min")
    void fifthFailureLocksForFifteenMinutes() {
        UserEntity user = user("admin", UserStatus.ACTIVE, "bcrypt-hash");
        user.getExtAttrs().put("loginFailureCount", AuthService.MAX_FAILURES - 1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("bad", "bcrypt-hash")).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> authService.login("admin", "bad"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertEquals(UserStatus.LOCKED.name(), user.getStatus());
        assertEquals(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC)
                .plus(AuthService.LOCK_DURATION), user.getLockedUntil());
        verify(auditLogService).recordLoginFailed(eq(TENANT_ID), eq(USER_ID), eq("admin"));
    }

    @Test
    @DisplayName("锁定窗口内即使密码正确也拒绝（40100）")
    void lockedUserRejectedEvenWithCorrectPassword() {
        UserEntity user = user("admin", UserStatus.LOCKED, "bcrypt-hash");
        user.setLockedUntil(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).plusMinutes(1));
        when(userMapper.selectOne(any())).thenReturn(user);

        BizException exception = assertThrows(BizException.class, () -> authService.login("admin", "pwd"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(userMapper, never()).updateById(user);
        verify(auditLogService).recordLoginFailed(eq(TENANT_ID), eq(USER_ID), eq("admin"));
    }

    @Test
    @DisplayName("锁定窗口过期后可重新登录并恢复 ACTIVE")
    void expiredLockAllowsLogin() {
        UserEntity user = user("admin", UserStatus.LOCKED, "bcrypt-hash");
        user.setLockedUntil(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC).minusMinutes(1));
        user.getExtAttrs().put("loginFailureCount", AuthService.MAX_FAILURES);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("pwd", "bcrypt-hash")).thenReturn(true);
        when(rbacRepository.findRoleCodesByUserId(TENANT_ID, USER_ID)).thenReturn(List.of(RoleCodes.ADMIN));

        AuthenticatedUser result = authService.login("admin", "pwd");

        assertEquals(USER_ID, result.userId());
        assertEquals(UserStatus.ACTIVE.name(), user.getStatus());
        assertTrue(!user.getExtAttrs().containsKey("loginFailureCount"));
        verify(userMapper).updateById(user);
    }

    @Test
    @DisplayName("登录租户取自 users.tenant_id，登录后清理 ThreadLocal")
    void loginUsesTenantFromUsersTable() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UserEntity user = user("admin", UserStatus.ACTIVE, "bcrypt-hash");
        when(rbacRepository.findUsersByUsername("admin"))
                .thenReturn(List.of(new RbacRepository.UsernameTenant(USER_ID, tenantId)));
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("pwd", "bcrypt-hash")).thenReturn(true);
        when(rbacRepository.findRoleCodesByUserId(tenantId, USER_ID)).thenReturn(List.of(RoleCodes.ADMIN));

        AuthenticatedUser result = authService.login("admin", "pwd");

        assertEquals(tenantId, result.tenantId());
        assertEquals(List.of(RoleCodes.ADMIN), result.roleCodes());
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.getTenantId(), "登录结束必须清理租户上下文");
    }

    @Test
    @DisplayName("同名账号跨租户：明确拒绝 40100")
    void loginRejectsUsernameSpanningTenants() {
        when(rbacRepository.findUsersByUsername("admin")).thenReturn(List.of(
                new RbacRepository.UsernameTenant(USER_ID, TENANT_ID),
                new RbacRepository.UsernameTenant(USER_ID, UUID.fromString("00000000-0000-0000-0000-000000000002"))));

        BizException exception = assertThrows(BizException.class, () -> authService.login("admin", "pwd"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("账号无租户：prod 明确拒绝")
    void loginRejectsMissingTenantInProd() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        AuthService prodService = new AuthService(userMapper, rbacRepository, auditLogService, passwordEncoder,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), prod);
        when(rbacRepository.findUsersByUsername("admin")).thenReturn(List.of(
                new RbacRepository.UsernameTenant(USER_ID, null)));

        BizException exception = assertThrows(BizException.class, () -> prodService.login("admin", "pwd"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("账号不存在：40100 且 user_id 为 NULL")
    void unknownUser() {
        when(userMapper.selectOne(any())).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> authService.login("ghost", "pwd"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(auditLogService).recordLoginFailed(eq(TENANT_ID), isNull(), eq("ghost"));
    }

    @Test
    @DisplayName("已停用账号：40100")
    void disabledUser() {
        when(userMapper.selectOne(any())).thenReturn(user("admin", UserStatus.DISABLED, "bcrypt-hash"));

        BizException exception = assertThrows(BizException.class, () -> authService.login("admin", "pwd"));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(auditLogService).recordLoginFailed(eq(TENANT_ID), eq(USER_ID), eq("admin"));
    }

    private static UserEntity user(String username, UserStatus status, String passwordHash) {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUsername(username);
        user.setStatus(status.name());
        user.setPasswordHash(passwordHash);
        user.setVersion(1);
        Map<String, Object> ext = new LinkedHashMap<>();
        user.setExtAttrs(ext);
        return user;
    }
}
