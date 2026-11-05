package com.hengce.aps.agent.prompt;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 某个 Prompt 键下的一个不可变版本。
 *
 * <p>版本号在同一 {@code (tenantId, promptKey)} 下单调递增；{@code active} 表示当前生效版本。
 * 内容可编辑（新增版本），不覆盖历史版本，从而支持回滚。</p>
 *
 * @param tenantId 租户
 * @param promptKey Prompt 键
 * @param version 版本号，从 1 开始
 * @param content 模板内容
 * @param note 版本说明
 * @param active 是否当前生效
 * @param createdAt 创建时间
 * @param createdBy 创建人，可为 null（系统自动生成为空）
 */
public record PromptVersion(UUID tenantId, String promptKey, int version, String content, String note,
                            boolean active, OffsetDateTime createdAt, UUID createdBy) {
}
