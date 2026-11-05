package com.hengce.aps.service.schedule.portfolio;

import com.hengce.aps.solver.api.ScorePoint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 评分轨迹截断工具：C54 要求 {@code scoreHistory}/{@code scoreTimeline} 最多 200 点。
 */
public final class ScoreTimelines {

    /**
     * 契约规定的轨迹点数上限。
     */
    public static final int MAX_POINTS = 200;

    private ScoreTimelines() {
    }

    /**
     * 截断结果。
     *
     * @param points 截断后的点
     * @param truncated 是否发生截断
     * @param timeToBestMs 首个最佳解（hard/soft 均最优）的相对毫秒；无点为 null
     */
    public record Timeline(List<ScorePoint> points, boolean truncated, Long timeToBestMs) {
    }

    /**
     * 均匀采样到最多 {@value #MAX_POINTS} 点，保留首尾。
     *
     * @param source 原始轨迹
     * @return 截断结果
     */
    public static Timeline limit(List<ScorePoint> source) {
        List<ScorePoint> points = source == null ? List.of() : source;
        Long timeToBestMs = firstBestMillis(points);
        if (points.size() <= MAX_POINTS) {
            return new Timeline(List.copyOf(points), false, timeToBestMs);
        }
        List<ScorePoint> sampled = new ArrayList<>(MAX_POINTS);
        long lastIndex = points.size() - 1L;
        for (int k = 0; k < MAX_POINTS; k++) {
            long index = Math.round(k * lastIndex / (double) (MAX_POINTS - 1));
            ScorePoint point = points.get((int) Math.min(index, lastIndex));
            if (sampled.isEmpty() || sampled.get(sampled.size() - 1) != point) {
                sampled.add(point);
            }
        }
        if (sampled.get(sampled.size() - 1) != points.get(points.size() - 1)) {
            sampled.set(sampled.size() - 1, points.get(points.size() - 1));
        }
        return new Timeline(List.copyOf(sampled), true, timeToBestMs);
    }

    /**
     * 首个最佳解的耗时（C54：{@code timeToBestMs} 是“首个最佳解耗时”，不是轨迹首点）。
     *
     * <p>按 hard→soft 字典序最大化，返回<b>首次</b>达到该分数的采样点耗时。</p>
     *
     * @param points 轨迹
     * @return 毫秒；无点时为 null
     */
    private static Long firstBestMillis(List<ScorePoint> points) {
        if (points.isEmpty()) {
            return null;
        }
        ScorePoint best = points.get(0);
        for (ScorePoint point : points) {
            if (compareScore(point, best) > 0) {
                best = point;
            }
        }
        return best.elapsedMs();
    }

    private static int compareScore(ScorePoint left, ScorePoint right) {
        int hard = worstNullAsMin(left.hardScore()).compareTo(worstNullAsMin(right.hardScore()));
        return hard != 0 ? hard : worstNullAsMin(left.softScore()).compareTo(worstNullAsMin(right.softScore()));
    }

    private static BigDecimal worstNullAsMin(BigDecimal score) {
        return score == null ? BigDecimal.valueOf(Long.MIN_VALUE) : score;
    }
}
