package com.hengce.aps.service.importexport;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 异步导入执行池：单实例固定 2 个线程，超出排队（任务保持 {@code PENDING}），不做拒绝。
 */
@Component
public class ImportJobExecutor {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "import-job-" + THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 提交执行任务；队列无界，调用方立即返回。
     *
     * @param task 任务
     */
    public void submit(Runnable task) {
        pool.execute(task);
    }

    /**
     * 停机时终止排队任务，由 Spring 生命周期回调。
     */
    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
