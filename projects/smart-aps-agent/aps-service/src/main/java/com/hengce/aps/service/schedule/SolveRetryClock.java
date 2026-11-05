package com.hengce.aps.service.schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;

/**
 * 求解重试的可注入时钟（C37 §25.8.1）。
 *
 * <p>生产实现 {@link SystemSolveRetryClock}；单测注入假时钟，既提供
 * {@code metrics.retryAt[]} 的确定性时间戳，也把退避改成“调度触发”而不用真实休眠。</p>
 *
 * <p>A0 起异步路径使用 {@link #schedule(Duration, Runnable)}：调用线程立即返回，
 * 30 秒退避不再占用求解工作线程；同步路径仍可用 {@link #await(Duration)} 阻塞调用方。</p>
 */
public interface SolveRetryClock {

    /**
     * 当前 UTC 时间，用于 {@code metrics.retryAt[]}。
     *
     * @return 当前时刻
     */
    Instant nowUtc();

    /**
     * 退避等待（仅同步路径使用）。
     *
     * @param delay 等待时长
     * @throws InterruptedException 等待被中断（取消/关闭）
     */
    void await(Duration delay) throws InterruptedException;

    /**
     * 非阻塞调度：{@code delay} 之后执行 {@code task}，调用线程立即返回。
     *
     * @param delay 延迟时长
     * @param task 到期任务
     * @return 可取消的句柄
     */
    Future<?> schedule(Duration delay, Runnable task);
}
