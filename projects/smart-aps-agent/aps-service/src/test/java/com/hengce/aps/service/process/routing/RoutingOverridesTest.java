package com.hengce.aps.service.process.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link RoutingOverrides} 三态读写单测（契约 C15 §20.3.1.1 / §20.3.1.3）。
 */
class RoutingOverridesTest {

    private static final UUID RESOURCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID SECONDARY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void buildInheritShouldNotWritePrimaryKey() {
        Map<String, Object> overrides = RoutingOverrides.build(RoutingNodeModes.INHERIT, null, null);
        assertFalse(overrides.containsKey(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.INHERIT, RoutingOverrides.primaryResourceMode(overrides));
    }

    @Test
    void buildFloatShouldWritePresentNullKey() {
        Map<String, Object> overrides = RoutingOverrides.build(RoutingNodeModes.FLOAT, null, null);
        assertTrue(overrides.containsKey(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertNull(overrides.get(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.FLOAT, RoutingOverrides.primaryResourceMode(overrides));
    }

    @Test
    void buildFixedShouldWriteUuidString() {
        Map<String, Object> overrides = RoutingOverrides.build(RoutingNodeModes.FIXED, RESOURCE_ID, null);
        assertEquals(RESOURCE_ID.toString(), overrides.get(RoutingOverrides.PRIMARY_RESOURCE_ID));
        assertEquals(RoutingNodeModes.FIXED, RoutingOverrides.primaryResourceMode(overrides));
        assertEquals(RESOURCE_ID, RoutingOverrides.primaryResourceId(overrides));
    }

    @Test
    void modeShouldDistinguishMissingKeyFromNull() {
        Map<String, Object> missing = new LinkedHashMap<>();
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put(RoutingOverrides.PRIMARY_RESOURCE_ID, null);
        assertEquals(RoutingNodeModes.INHERIT, RoutingOverrides.primaryResourceMode(missing));
        assertEquals(RoutingNodeModes.FLOAT, RoutingOverrides.primaryResourceMode(explicitNull));
        assertEquals(RoutingNodeModes.INHERIT, RoutingOverrides.primaryResourceMode(null));
    }

    @Test
    void secondaryResourceIdsShouldKeepThreeStates() {
        Map<String, Object> missing = RoutingOverrides.build(RoutingNodeModes.INHERIT, null, null);
        assertFalse(missing.containsKey(RoutingOverrides.SECONDARY_RESOURCE_IDS));
        assertNull(RoutingOverrides.secondaryResourceIds(missing));

        Map<String, Object> explicitEmpty = RoutingOverrides.build(
                RoutingNodeModes.INHERIT, null, List.of());
        assertTrue(explicitEmpty.containsKey(RoutingOverrides.SECONDARY_RESOURCE_IDS));
        assertTrue(RoutingOverrides.secondaryResourceIds(explicitEmpty).isEmpty());

        Map<String, Object> explicitList = RoutingOverrides.build(
                RoutingNodeModes.INHERIT, null, List.of(SECONDARY_ID));
        assertEquals(List.of(SECONDARY_ID), RoutingOverrides.secondaryResourceIds(explicitList));
    }
}
