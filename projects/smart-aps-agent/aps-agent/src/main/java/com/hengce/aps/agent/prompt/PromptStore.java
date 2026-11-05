package com.hengce.aps.agent.prompt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Prompt 版本存储：模板与版本可持久化，并支持「当前版本」与「回滚到上一版」。
 *
 * <p>实现方负责租户隔离；同一 {@code (tenantId, promptKey)} 同一时刻最多一个 active。
 * 该接口不是只读常量视图：{@link #register} / {@link #activate} / {@link #rollback} 都会写库。</p>
 */
public interface PromptStore {

    /**
     * 新增一个版本（版本号自动取当前最大值 + 1），默认不激活。
     *
     * @param promptKey Prompt 键
     * @param content 模板内容
     * @param note 版本说明，可为 null
     * @param createdBy 创建人，可为 null
     * @return 新建版本
     */
    PromptVersion register(String promptKey, String content, String note, UUID createdBy);

    /**
     * 查询当前生效版本。
     *
     * @param promptKey Prompt 键
     * @return 无生效版本时为空
     */
    Optional<PromptVersion> findActive(String promptKey);

    /**
     * 查询指定版本。
     *
     * @param promptKey Prompt 键
     * @param version 版本号
     * @return 不存在时为空
     */
    Optional<PromptVersion> find(String promptKey, int version);

    /**
     * 列出全部版本，按版本号升序。
     *
     * @param promptKey Prompt 键
     * @return 版本列表
     */
    List<PromptVersion> list(String promptKey);

    /**
     * 激活指定版本；同一键的其他版本自动取消激活。
     *
     * @param promptKey Prompt 键
     * @param version 版本号
     * @return 激活后的版本
     */
    PromptVersion activate(String promptKey, int version);

    /**
     * 回滚到「当前 active 之前的最近一个版本」并激活。
     *
     * @param promptKey Prompt 键
     * @return 新的生效版本；无当前版本或无更早版本时为空
     */
    Optional<PromptVersion> rollback(String promptKey);

    /**
     * 是否已存在任何版本。
     *
     * @param promptKey Prompt 键
     * @return true 表示已初始化
     */
    boolean hasAny(String promptKey);

    /**
     * 幂等初始化：无任何版本时写入并激活版本 1；已有版本时返回当前生效版本（无 active 则激活最新版）。
     *
     * @param promptKey Prompt 键
     * @param content 初始模板
     * @param note 版本说明
     * @return 当前生效版本
     */
    PromptVersion ensureActive(String promptKey, String content, String note);
}
