package com.hengce.aps.service.schedule.portfolio;

/**
 * 单个候选的不可变描述。
 *
 * @param profile 策略画像
 * @param seed 实际随机种子
 * @param order 候选顺序（0 起）
 */
public record CandidateSpec(CandidateProfile profile, long seed, int order) {
}
