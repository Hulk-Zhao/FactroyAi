package com.hengce.aps.service.schedule.portfolio;

/**
 * 候选策略画像：seed 偏移 + moveSelectors 组合。
 *
 * <p>4.0 阶段只保留基准、子链、ruin、warm 及其组合；late 画像留待基准数据证明收益后再启用。</p>
 */
public enum CandidateProfile {

    /**
     * 基准画像：不改 moveSelectors，完全复现请求配置。
     */
    BASELINE("baseline", "", 0, false),

    /**
     * 子链优先：强化工序链上的移动。
     */
    SUBCHAIN("subchain", "subchain", 1, false),

    /**
     * 组合破坏：子链 + ruin。
     */
    RUIN("ruin", "subchain,ruin", 2, false),

    /**
     * 冻结热启动：题面已有冻结分配时复用其结构，仅换 seed。
     */
    WARM("warm", "", 3, true),

    /**
     * 热启动 + ruin：冻结结构 + 破坏重建。
     */
    WARM_RUIN("warm-ruin", "subchain,ruin", 4, true);

    private final String label;

    private final String moveSelectors;

    private final int seedOffset;

    private final boolean requiresWarmStart;

    CandidateProfile(String label, String moveSelectors, int seedOffset, boolean requiresWarmStart) {
        this.label = label;
        this.moveSelectors = moveSelectors;
        this.seedOffset = seedOffset;
        this.requiresWarmStart = requiresWarmStart;
    }

    /**
     * 落库/日志用画像名，必须与 C54 winnerProfile 字符串一致。
     *
     * @return 画像名
     */
    public String label() {
        return label;
    }

    /**
     * moveSelectors 覆盖值；空串表示沿用请求配置。
     *
     * @return moveSelectors
     */
    public String moveSelectors() {
        return moveSelectors;
    }

    /**
     * seed 偏移，保证候选之间不共享随机序列。
     *
     * @return 偏移
     */
    public int seedOffset() {
        return seedOffset;
    }

    /**
     * 是否要求题面存在冻结分配。
     *
     * @return 要求为 true
     */
    public boolean requiresWarmStart() {
        return requiresWarmStart;
    }
}
