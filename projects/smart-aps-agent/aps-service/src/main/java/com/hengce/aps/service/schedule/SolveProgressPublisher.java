package com.hengce.aps.service.schedule;

import com.hengce.aps.solver.api.task.SolveProgress;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 求解进度发布器：缓存最新快照并以 Spring 事件发布，由 Web 层桥接到 STOMP。
 *
 * <p>进度推送通道通过 {@code aps.solve.progress-push} 控制，缺省 {@code stomp}；
 * 设置为 {@code none} 时只缓存不广播（前端回退轮询）。事实源始终是
 * {@code GET /solve-tasks/{id}}，本类不替代查询。</p>
 */
@Service
public class SolveProgressPublisher {

    /**
     * 最新进度缓存：taskId → 快照。
     */
    private final ConcurrentMap<Long, SolveProgress> latest = new ConcurrentHashMap<>();

    /**
     * Spring 事件发布器。
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 是否广播到 STOMP。
     */
    private final boolean broadcastEnabled;

    /**
     * 构造发布器。
     *
     * @param eventPublisher Spring 事件发布器
     * @param progressPush 推送通道：stomp / none
     */
    public SolveProgressPublisher(ApplicationEventPublisher eventPublisher,
                                  @Value("${aps.solve.progress-push:stomp}") String progressPush) {
        this.eventPublisher = eventPublisher;
        this.broadcastEnabled = progressPush == null || !"none".equalsIgnoreCase(progressPush.trim());
    }

    /**
     * 发布进度并更新最新快照。
     *
     * @param progress 进度消息
     */
    public void publish(SolveProgress progress) {
        if (progress == null) {
            return;
        }
        latest.put(progress.taskId(), progress);
        if (broadcastEnabled) {
            eventPublisher.publishEvent(progress);
        }
    }

    /**
     * 读取最新快照。
     *
     * @param taskId 求解任务主键
     * @return 最新进度
     */
    public Optional<SolveProgress> latest(long taskId) {
        return Optional.ofNullable(latest.get(taskId));
    }

    /**
     * 清理快照，避免长期运行的内存增长。
     *
     * @param taskId 求解任务主键
     */
    public void forget(long taskId) {
        latest.remove(taskId);
    }
}
