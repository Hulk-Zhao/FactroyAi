package com.hengce.aps.agent.testsupport;

import com.hengce.aps.agent.prompt.PromptStore;
import com.hengce.aps.agent.prompt.PromptVersion;
import com.hengce.aps.common.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 测试专用内存 Prompt 存储：与 {@code JdbcPromptStore} 语义一致，仅用于无数据库的单测。
 *
 * <p>仅在 src/test 作用域存在。{@link #failNextEnsure} 用于制造步骤失败，
 * 验证「检查点后失败 → 续跑」。</p>
 */
public final class InMemoryPromptStore implements PromptStore {

    private final Map<String, List<PromptVersion>> store = new HashMap<>();

    private RuntimeException nextEnsureFailure;

    /**
     * 让下一次 {@link #ensureActive} 抛错（只生效一次）。
     *
     * @param failure 待抛出异常
     */
    public void failNextEnsure(RuntimeException failure) {
        this.nextEnsureFailure = failure;
    }

    @Override
    public PromptVersion register(String promptKey, String content, String note, UUID createdBy) {
        List<PromptVersion> current = store.computeIfAbsent(promptKey, key -> new ArrayList<>());
        int nextVersion = current.stream().mapToInt(PromptVersion::version).max().orElse(0) + 1;
        PromptVersion version = new PromptVersion(TenantContext.getTenantId(), promptKey, nextVersion,
                content, note, false, OffsetDateTime.now(), createdBy);
        current.add(version);
        return version;
    }

    @Override
    public Optional<PromptVersion> findActive(String promptKey) {
        return store.getOrDefault(promptKey, List.of()).stream()
                .filter(PromptVersion::active)
                .findFirst();
    }

    @Override
    public Optional<PromptVersion> find(String promptKey, int version) {
        return store.getOrDefault(promptKey, List.of()).stream()
                .filter(item -> item.version() == version)
                .findFirst();
    }

    @Override
    public List<PromptVersion> list(String promptKey) {
        return List.copyOf(store.getOrDefault(promptKey, List.of()));
    }

    @Override
    public PromptVersion activate(String promptKey, int version) {
        List<PromptVersion> current = store.get(promptKey);
        if (current == null || current.stream().noneMatch(item -> item.version() == version)) {
            throw new IllegalArgumentException("Prompt 版本不存在: " + promptKey + "#" + version);
        }
        List<PromptVersion> updated = current.stream()
                .map(item -> withActive(item, item.version() == version))
                .toList();
        store.put(promptKey, updated);
        return find(promptKey, version).orElseThrow();
    }

    @Override
    public Optional<PromptVersion> rollback(String promptKey) {
        Optional<PromptVersion> active = findActive(promptKey);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        int currentVersion = active.get().version();
        Optional<Integer> previous = store.getOrDefault(promptKey, List.of()).stream()
                .map(PromptVersion::version)
                .filter(version -> version < currentVersion)
                .max(Integer::compareTo);
        return previous.map(version -> activate(promptKey, version));
    }

    @Override
    public boolean hasAny(String promptKey) {
        return !store.getOrDefault(promptKey, List.of()).isEmpty();
    }

    @Override
    public PromptVersion ensureActive(String promptKey, String content, String note) {
        if (nextEnsureFailure != null) {
            RuntimeException failure = nextEnsureFailure;
            nextEnsureFailure = null;
            throw failure;
        }
        Optional<PromptVersion> active = findActive(promptKey);
        if (active.isPresent()) {
            return active.get();
        }
        if (!hasAny(promptKey)) {
            PromptVersion created = register(promptKey, content, note, null);
            return activate(promptKey, created.version());
        }
        int latest = store.get(promptKey).stream().mapToInt(PromptVersion::version).max().orElse(1);
        return activate(promptKey, latest);
    }

    private static PromptVersion withActive(PromptVersion version, boolean active) {
        return new PromptVersion(version.tenantId(), version.promptKey(), version.version(),
                version.content(), version.note(), active, version.createdAt(), version.createdBy());
    }
}
