package com.hengce.aps.common.idempotency;

import java.time.Duration;

/**
 * 幂等键存储抽象。
 *
 * <p>MVP 使用进程内实现；接入 Redis 时仅需替换实现：{@code SET key value NX PX ttl} 对应
 * {@link #acquire(String, Duration)}，删除对应 {@link #release(String)}。</p>
 */
public interface IdempotencyStore {

    /**
     * 占用幂等键。
     *
     * @param key 已包含租户维度的完整键
     * @param ttl 占用有效期
     * @return {@code true} 表示首次占用成功；{@code false} 表示已存在且未过期
     */
    boolean acquire(String key, Duration ttl);

    /**
     * 释放幂等键，用于请求失败后允许客户端重试。
     *
     * @param key 完整键
     */
    void release(String key);
}
