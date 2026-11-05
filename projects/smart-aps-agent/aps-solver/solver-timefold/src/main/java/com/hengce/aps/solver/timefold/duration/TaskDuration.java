package com.hengce.aps.solver.timefold.duration;

/**
 * 单个工序的时长分解，单位均为秒。
 *
 * @param setupSeconds 换型/准备时长
 * @param runSeconds 加工时长
 */
public record TaskDuration(
        long setupSeconds,
        long runSeconds
) {

    /**
     * 总占用时长：换型 + 加工。
     *
     * @return 总秒数
     */
    public long totalSeconds() {
        return setupSeconds + runSeconds;
    }
}
