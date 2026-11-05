package com.hengce.aps.service.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.mapper.rbac.UserMapper;
import com.hengce.aps.infrastructure.persistence.rbac.RbacRepository;
import com.hengce.aps.infrastructure.persistence.rbac.UserEntity;
import com.hengce.aps.service.audit.AuditLogService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * C30 {@code GET /me} 返回角色与权限并集测试。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");

    @Mock
    private UserMapper userMapper;

    @Mock
    private RbacRepository rbacRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PermissionService permissionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, rbacRepository, passwordEncoder, auditLogService,
                permissionService);
        TenantContext.clear();
    }

    @Test
    @DisplayName("/me 返回用户基本信息、角色码与权限码并集，且不含口令")
    void meReturnsRolesAndPermissions() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUsername("admin");
        user.setDisplayName("管理员");
        user.setEmail("admin@example.com");
        user.setStatus("ACTIVE");
        user.setVersion(2);
        user.setLastLoginAt(OffsetDateTime.parse("2026-11-03T10:00:00Z"));
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(rbacRepository.findRoleCodesByUserId(TENANT_ID, USER_ID)).thenReturn(List.of("ADMIN"));
        when(rbacRepository.findPermissionCodesByUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of("SYS_USER", "SYS_ROLE", "SYS_AUDIT"));

        MeView view = userService.me(USER_ID);

        assertEquals(USER_ID, view.id());
        assertEquals("admin", view.username());
        assertEquals("管理员", view.displayName());
        assertEquals(List.of("ADMIN"), view.roleCodes());
        assertEquals(List.of("SYS_USER", "SYS_ROLE", "SYS_AUDIT"), view.permissions());
        assertEquals(2, view.version());
        assertFalse(view.toString().contains("password"));
    }
}
