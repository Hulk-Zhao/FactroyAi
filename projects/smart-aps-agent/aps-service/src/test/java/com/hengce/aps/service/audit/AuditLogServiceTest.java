package com.hengce.aps.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogPage;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRepository;
import com.hengce.aps.infrastructure.persistence.audit.AuditLogRow;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C31 审计写入脱敏与查询过滤测试。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository);
        TenantContext.clear();
    }

    @Test
    @DisplayName("审计写入：动作/资源类型正确，password/token 与嵌套 secret 被脱敏")
    @SuppressWarnings("unchecked")
    void recordRedactsSensitiveKeys() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("username", "admin");
        before.put("password", "plain-text");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("token", "jwt-token");
        after.put("profile", Map.of("apiKey", "dev-key", "displayName", "管理员"));
        after.put("roles", List.of(Map.of("secret", "hidden", "code", "ADMIN")));

        auditLogService.record(AuditEventType.USER_CREATED, "demo-id", before, after);

        ArgumentCaptor<Map<String, Object>> beforeCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogRepository).record(eq(TENANT_ID), eq(AuditEventType.USER_CREATED.name()),
                eq("USER"), eq("demo-id"), beforeCaptor.capture(), afterCaptor.capture());

        assertEquals("admin", beforeCaptor.getValue().get("username"));
        assertEquals(AuditValues.REDACTED, beforeCaptor.getValue().get("password"));
        assertEquals(AuditValues.REDACTED, afterCaptor.getValue().get("token"));
        Map<String, Object> profile = (Map<String, Object>) afterCaptor.getValue().get("profile");
        assertEquals(AuditValues.REDACTED, profile.get("apiKey"));
        assertEquals("管理员", profile.get("displayName"));
        List<?> roles = (List<?>) afterCaptor.getValue().get("roles");
        Map<?, ?> role = (Map<?, ?>) roles.get(0);
        assertEquals(AuditValues.REDACTED, role.get("secret"));
        assertEquals("ADMIN", role.get("code"));
        assertEquals("plain-text", before.get("password"));
    }

    @Test
    @DisplayName("登录失败审计：独立事务、显式 user_id、不写敏感快照")
    void recordLoginFailed() {
        auditLogService.recordLoginFailed(TENANT_ID, USER_ID, "admin");

        verify(auditLogRepository).record(eq(TENANT_ID), eq(USER_ID),
                eq(AuditEventType.AUTH_LOGIN_FAILED.name()), eq("USER"), eq("admin"), isNull(), isNull());
    }

    @Test
    @DisplayName("审计查询：action/resourceType/resourceId/userId/from/to 全部透传，升序与分页正确")
    void queryAllFilters() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-04T00:00:00Z");
        AuditLogRow row = new AuditLogRow(42L, USER_ID, "AUTH_LOGIN", "USER", "admin",
                Map.of("username", "admin"), null, "10.0.0.1", "trace-1",
                OffsetDateTime.parse("2026-11-02T08:00:00Z"));
        when(auditLogRepository.query(any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                anyInt(), anyInt())).thenReturn(new AuditLogPage(List.of(row), 1L));
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPage(2);
        pageQuery.setSize(10);

        PageResult<AuditLogView> result = auditLogService.query(pageQuery, "AUTH_LOGIN", "USER", "admin",
                USER_ID, from, to, "createdAt,asc");

        verify(auditLogRepository).query(eq(TENANT_ID), eq("AUTH_LOGIN"), eq("USER"), eq("admin"),
                eq(USER_ID), eq(from), eq(to), eq(true), eq(10), eq(10));
        assertEquals(1, result.items().size());
        assertEquals(42L, result.items().get(0).id());
        assertEquals("trace-1", result.items().get(0).traceId());
        assertEquals("10.0.0.1", result.items().get(0).clientIp());
        assertEquals("admin", result.items().get(0).resourceId());
    }

    @Test
    @DisplayName("审计查询：仅部分过滤条件时也能正确透传")
    void queryPartialFilters() {
        when(auditLogRepository.query(any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                anyInt(), anyInt())).thenReturn(new AuditLogPage(List.of(), 0L));

        PageResult<AuditLogView> result = auditLogService.query(null, null, "PLAN", null, null,
                null, null, null);

        verify(auditLogRepository).query(eq(TENANT_ID), isNull(), eq("PLAN"), isNull(), isNull(),
                isNull(), isNull(), eq(false), eq(0), eq(20));
        assertEquals(0, result.total());
        assertEquals(20, result.size());
        assertTrue(result.items().isEmpty());
    }

    @Test
    @DisplayName("非法 sort 返回 40000")
    void invalidSort() {
        BizException exception = assertThrows(BizException.class, () -> auditLogService.query(new PageQuery(),
                null, null, null, null, null, null, "createdAt,ASC"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
    }
}
