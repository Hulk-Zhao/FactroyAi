package com.hengce.aps.web.idempotency;

import com.hengce.aps.common.idempotency.IdempotencyStore;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内幂等键存储（MVP）。
 *
 * <p>多实例部署时替换为 Redis 实现；本实现仅保证单实例内并发安全。</p>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Long> entries = new ConcurrentHashMap<>();

    @Override
    public boolean acquire(String key, Duration ttl) {
        purgeExpiredIfNecessary();
        long expiresAt = System.currentTimeMillis() + ttl.toMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);
        entries.compute(key, (ignored, existing) -> {
            if (existing != null && existing > System.currentTimeMillis()) {
                return existing;
            }
            acquired.set(true);
            return expiresAt;
        });
        return acquired.get();
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }

    private void purgeExpiredIfNecessary() {
        if (entries.size() < CLEANUP_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}
