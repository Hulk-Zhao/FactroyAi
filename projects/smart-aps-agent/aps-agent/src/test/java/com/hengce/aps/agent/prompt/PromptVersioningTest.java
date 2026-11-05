package com.hengce.aps.agent.prompt;

import com.hengce.aps.agent.testsupport.InMemoryPromptStore;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 版本管理单测：注册、激活、回滚到上一版、幂等初始化。
 */
class PromptVersioningTest {

    private final InMemoryPromptStore store = new InMemoryPromptStore();

    @Test
    void registerDoesNotActivateAndKeepsHistory() {
        PromptVersion first = store.register("agent.test", "v1", "初始", null);
        PromptVersion second = store.register("agent.test", "v2", "调整", null);

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertFalse(first.active());
        assertFalse(second.active());
        assertTrue(store.list("agent.test").stream().noneMatch(PromptVersion::active));
    }

    @Test
    void activateSwitchesExactlyOneVersion() {
        store.register("agent.test", "v1", null, null);
        store.register("agent.test", "v2", null, null);

        PromptVersion activated = store.activate("agent.test", 2);

        assertTrue(activated.active());
        assertTrue(store.findActive("agent.test").isPresent());
        assertEquals(2, store.findActive("agent.test").orElseThrow().version());
        assertTrue(store.list("agent.test").stream().filter(PromptVersion::active).count() == 1);
    }

    @Test
    void rollbackGoesToPreviousVersionAndStopsAtOldest() {
        store.register("agent.test", "v1", null, null);
        store.register("agent.test", "v2", null, null);
        store.register("agent.test", "v3", null, null);
        store.activate("agent.test", 3);

        PromptVersion afterFirstRollback = store.rollback("agent.test").orElseThrow();
        assertEquals(2, afterFirstRollback.version());

        PromptVersion afterSecondRollback = store.rollback("agent.test").orElseThrow();
        assertEquals(1, afterSecondRollback.version());

        assertTrue(store.rollback("agent.test").isEmpty());
    }

    @Test
    void ensureActiveSeedsVersionOneOnlyOnce() {
        PromptVersion seeded = store.ensureActive("agent.test", "默认模板", "auto-seed");
        assertEquals(1, seeded.version());
        assertEquals("默认模板", seeded.content());

        PromptVersion again = store.ensureActive("agent.test", "不会覆盖", "auto-seed");
        assertEquals(1, again.version());
        assertEquals(1, store.list("agent.test").size());
    }

    @Test
    void ensureActiveRecoversWhenNoActiveButHistoryExists() {
        store.register("agent.test", "v1", null, null);
        store.register("agent.test", "v2", null, null);

        PromptVersion active = store.ensureActive("agent.test", "不会新建", "auto-seed");

        assertEquals(2, active.version());
        assertEquals(2, store.list("agent.test").size());
    }

    @Test
    void listIsOrderedByVersion() {
        store.register("agent.test", "v1", null, null);
        store.register("agent.test", "v2", null, null);

        List<PromptVersion> versions = store.list("agent.test");

        assertEquals(List.of(1, 2), versions.stream().map(PromptVersion::version).toList());
    }
}
