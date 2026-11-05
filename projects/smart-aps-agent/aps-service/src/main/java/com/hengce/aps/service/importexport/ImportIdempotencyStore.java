package com.hengce.aps.service.importexport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 导入幂等令牌存储：同一租户 + 实体 + 令牌只执行一次，重复请求返回历史结果。
 *
 * <p>MVP 使用进程内存储；接入 Redis 后替换本组件即可，令牌语义不变。</p>
 */
@Component
public class ImportIdempotencyStore {

    private final Map<String, ImportResult> executed = new ConcurrentHashMap<>();

    /**
     * 查找已执行结果。
     *
     * @param tenantId 租户
     * @param entity 实体
     * @param token 令牌
     * @return 历史结果；未命中返回 {@code null}
     */
    public ImportResult find(String tenantId, String entity, String token) {
        return executed.get(key(tenantId, entity, token));
    }

    /**
     * 记录执行结果。
     *
     * @param tenantId 租户
     * @param entity 实体
     * @param token 令牌
     * @param result 执行结果
     */
    public void save(String tenantId, String entity, String token, ImportResult result) {
        executed.put(key(tenantId, entity, token), result);
    }

    /**
     * 清理指定结果（失败场景允许重试）。
     *
     * @param tenantId 租户
     * @param entity 实体
     * @param token 令牌
     */
    public void remove(String tenantId, String entity, String token) {
        executed.remove(key(tenantId, entity, token));
    }

    private String key(String tenantId, String entity, String token) {
        return tenantId + ":" + entity + ":" + token;
    }
}
