package com.hengce.aps.service.importexport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 异步导入令牌存储：同一租户 + 实体 + 令牌映射到已受理任务与其内容摘要。
 *
 * <p>任务本身持久化在 {@code import_job}；令牌映射为进程内缓存（与同步幂等存储同口径）。
 * 进程重启后仅丢失令牌→任务映射，内容哈希仍在途唯一约束兜底。</p>
 */
@Component
public class ImportJobTokenStore {

    /**
     * 令牌对应任务。
     *
     * @param jobId 任务标识
     * @param contentHash 受理时的内容摘要
     */
    public record TokenEntry(UUID jobId, String contentHash) {
    }

    private final Map<String, TokenEntry> jobs = new ConcurrentHashMap<>();

    /**
     * 查找令牌对应的任务。
     *
     * @param tenantId 租户
     * @param entity 实体 slug
     * @param token 令牌
     * @return 任务条目；未命中返回 {@code null}
     */
    public TokenEntry find(String tenantId, String entity, String token) {
        return jobs.get(key(tenantId, entity, token));
    }

    /**
     * 记录令牌与任务、内容摘要的映射。
     *
     * @param tenantId 租户
     * @param entity 实体 slug
     * @param token 令牌
     * @param jobId 任务标识
     * @param contentHash 内容摘要
     */
    public void save(String tenantId, String entity, String token, UUID jobId, String contentHash) {
        jobs.put(key(tenantId, entity, token), new TokenEntry(jobId, contentHash));
    }

    /**
     * 清理失效映射。
     *
     * @param tenantId 租户
     * @param entity 实体 slug
     * @param token 令牌
     */
    public void remove(String tenantId, String entity, String token) {
        jobs.remove(key(tenantId, entity, token));
    }

    private String key(String tenantId, String entity, String token) {
        return tenantId + ":" + entity + ":" + token;
    }
}
