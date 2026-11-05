package com.hengce.aps.service.schedule.portfolio;

/**
 * 求解工作线程的排队等待上下文。
 *
 * <p>生命周期在把任务交给工作线程时记录“从提交到真正开始执行”的等待毫秒数，
 * Portfolio 运行时把它计入 {@code metrics.portfolio.slotWaitMs}，让排队时间可被观测。</p>
 */
public final class SolveQueueContext {

    private static final ThreadLocal<Long> QUEUE_WAIT_MS = new ThreadLocal<>();

    private SolveQueueContext() {
    }

    /**
     * 设置当前线程对应的排队等待。
     *
     * @param queueWaitMs 排队等待毫秒数
     */
    public static void set(long queueWaitMs) {
        QUEUE_WAIT_MS.set(Math.max(0, queueWaitMs));
    }

    /**
     * 读取排队等待。
     *
     * @return 毫秒数；未设置为 0
     */
    public static long current() {
        Long value = QUEUE_WAIT_MS.get();
        return value == null ? 0 : value;
    }

    /**
     * 清理，必须在工作线程归还池前调用。
     */
    public static void clear() {
        QUEUE_WAIT_MS.remove();
    }
}
