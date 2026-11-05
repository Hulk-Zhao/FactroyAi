package com.hengce.aps.service.sandbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 场景摘要（C32 列表/创建响应），不含 {@code snapshot}，避免列表携带大对象。
 *
 * @param id 场景主键
 * @param name 名称
 * @param type BASELINE / WHAT_IF / ARCHIVED
 * @param parentSandboxId 父场景
 * @param expiresAt 过期时间
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 */
public record SandboxSummaryView(
        UUID id,
        String name,
        String type,
        UUID parentSandboxId,
        OffsetDateTime expiresAt,
        int version,
        OffsetDateTime createdAt) {
}
