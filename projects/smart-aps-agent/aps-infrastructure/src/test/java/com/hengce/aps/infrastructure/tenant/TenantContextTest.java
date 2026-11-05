package com.hengce.aps.infrastructure.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TenantContext 设置、默认回落与清理测试。
 */
class TenantContextTest {

    private static final UUID EXPLICIT_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldFallbackToDefaultTenant() {
        assertFalse(TenantContext.hasExplicitTenant());
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.getTenantId());
    }

    @Test
    void shouldSupportExplicitOverrideAndClear() {
        TenantContext.setTenantId(EXPLICIT_TENANT);

        assertTrue(TenantContext.hasExplicitTenant());
        assertEquals(EXPLICIT_TENANT, TenantContext.getTenantId());

        TenantContext.clear();

        assertFalse(TenantContext.hasExplicitTenant());
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.getTenantId());
    }

    @Test
    void shouldSetTenantFromString() {
        TenantContext.setTenantId(EXPLICIT_TENANT.toString());
        assertEquals(EXPLICIT_TENANT, TenantContext.getTenantId());

        TenantContext.setTenantId("  ");
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.getTenantId());

        assertThrows(IllegalArgumentException.class, () -> TenantContext.setTenantId("not-a-uuid"));
    }

    @Test
    void shouldResetOverrideWhenSetNull() {
        TenantContext.setTenantId(EXPLICIT_TENANT);
        TenantContext.setTenantId((UUID) null);

        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.getTenantId());
    }
}
