package com.hengce.aps.service.schedule;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 生产用重试时钟：UTC 系统时间 + 非阻塞调度退避。
 *
 * <p>A0 起异步重试由本类的单线程调度器在 {@code delay} 之后触发，
 * 不再用 {@link Thread#sleep(long)} 占用求解工作线程。</p>
 */
@Component
public class SystemSolveRetryClock implements SolveRetryClock {

    /**
     * 时间源。
     */
    private final Clock clock;

    /**
     * 退避调度器（守护线程，只负责到期触发）。
     */
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "aps-solve-retry");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 使用系统 UTC 时钟构造。
     */
    public SystemSolveRetryClock() {
        this(Clock.systemUTC());
    }

    /**
     * 指定时间源构造（测试或定制部署）。
     *
     * @param clock 时间源
     */
    public SystemSolveRetryClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Instant nowUtc() {
        return clock.instant();
    }

    @Override
    public void await(Duration delay) throws InterruptedException {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        Thread.sleep(delay.toMillis());
    }

    @Override
    public Future<?> schedule(Duration delay, Runnable task) {
        long millis = delay == null || delay.isZero() || delay.isNegative() ? 0L : delay.toMillis();
        return retryScheduler.schedule(task, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * 关闭退避调度器。
     */
    @PreDestroy
    public void shutdown() {
        retryScheduler.shutdownNow();
    }
}
