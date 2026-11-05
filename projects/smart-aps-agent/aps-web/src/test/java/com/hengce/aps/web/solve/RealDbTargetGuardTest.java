package com.hengce.aps.web.solve;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 真库库名白名单守卫单测：只验证校验函数，不连接任何真实库。
 */
class RealDbTargetGuardTest {

    @Test
    void rejectsDevDatabaseBeforeClean() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> RealDbTargetGuard.requireCleanAllowed("aps_local"));
        System.out.println("[guard] " + exception.getMessage());
        assertTrue(exception.getMessage().contains("aps_local"), exception.getMessage());
        assertTrue(exception.getMessage().contains("aps_test"), exception.getMessage());
        assertTrue(exception.getMessage().contains("aps_m2_"), exception.getMessage());
    }

    @Test
    void rejectsBaselineVariantBeforeClean() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> RealDbTargetGuard.requireCleanAllowed("aps_local_2"));
        System.out.println("[guard] " + exception.getMessage());
        assertTrue(exception.getMessage().contains("aps_local_2"), exception.getMessage());
    }

    @Test
    void rejectsBlankOrNullDatabaseName() {
        assertThrows(IllegalStateException.class,
                () -> RealDbTargetGuard.requireCleanAllowed(null));
        assertThrows(IllegalStateException.class,
                () -> RealDbTargetGuard.requireCleanAllowed("  "));
    }

    @Test
    void allowsControlledTestDatabases() {
        assertDoesNotThrow(() -> RealDbTargetGuard.requireCleanAllowed("aps_m2_se3"));
        assertDoesNotThrow(() -> RealDbTargetGuard.requireCleanAllowed("aps_m2_mrp"));
        assertDoesNotThrow(() -> RealDbTargetGuard.requireCleanAllowed("aps_test_ci"));
    }
}
