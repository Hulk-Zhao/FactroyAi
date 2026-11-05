package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link RealDbEnvironment} 的库名硬门禁单测：不连接任何真实库。
 *
 * <p>环境变量无法在 JUnit 运行时注入，这里用等价的系统属性覆盖库名，
 * 验证“显式指定 {@code APS_TEST_DB_NAME=aps_local}”时立刻失败。</p>
 */
class RealDbEnvironmentGuardTest {

    private static final String NAME_SYSTEM_PROPERTY = "aps.test.db.name";

    @Test
    void explicitBaselineNameShouldFailFastBeforeConnecting() {
        String previous = System.getProperty(NAME_SYSTEM_PROPERTY);
        System.setProperty(NAME_SYSTEM_PROPERTY, "aps_local");
        try {
            assertEquals("aps_local", RealDbEnvironment.databaseName(),
                    "系统属性应优先于 APS_TEST_DB_NAME 被解析");
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> RealDbEnvironment.requireTestDatabase(RealDbEnvironment.databaseName()));
            System.out.println("[real-db-guard] " + exception.getMessage());
            assertTrue(exception.getMessage().contains("aps_local"), exception.getMessage());
            assertTrue(exception.getMessage().contains("基线库"), exception.getMessage());
        } finally {
            restore(previous);
        }
    }

    @Test
    void baselineVariantShouldFailFastBeforeConnecting() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> RealDbEnvironment.requireTestDatabase("aps_local_2"));
        System.out.println("[real-db-guard] " + exception.getMessage());
        assertTrue(exception.getMessage().contains("aps_local_2"), exception.getMessage());
    }

    @Test
    void emptyNameShouldFailFast() {
        assertThrows(IllegalStateException.class, () -> RealDbEnvironment.requireTestDatabase(null));
        assertThrows(IllegalStateException.class, () -> RealDbEnvironment.requireTestDatabase("  "));
    }

    @Test
    void controlledTestDatabaseNamesShouldPassGate() {
        assertDoesNotThrow(() -> RealDbEnvironment.requireTestDatabase("aps_test_shared"));
        assertDoesNotThrow(() -> RealDbEnvironment.requireTestDatabase("aps_m2_se4"));
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(NAME_SYSTEM_PROPERTY);
        } else {
            System.setProperty(NAME_SYSTEM_PROPERTY, previous);
        }
    }
}
