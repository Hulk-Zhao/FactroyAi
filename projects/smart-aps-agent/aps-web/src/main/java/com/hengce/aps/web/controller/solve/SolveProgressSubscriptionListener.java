package com.hengce.aps.web.controller.solve;

import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.solver.api.task.SolveProgress;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.web.security.SolveProgressWebSocketAuthInterceptor;
import com.hengce.aps.web.security.StompAuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * 订阅即推快照：客户端订阅 {@code /topic/solve-tasks/{id}} 时主动推一条当前进度。
 *
 * <p>快照从生命周期服务读取（事实源），随后增量由 {@link SolveProgressStompBridge}
 * 继续广播；这样断线重连后也能立即恢复展示。</p>
 */
@Component
public class SolveProgressSubscriptionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveProgressSubscriptionListener.class);

    /**
     * 求解进度主题前缀。
     */
    private static final String TOPIC_PREFIX = SolveProgressWebSocketAuthInterceptor.TOPIC_PREFIX;

    /**
     * 消息发送模板。
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 生命周期服务。
     */
    private final SolveTaskLifecycleService lifecycleService;

    /**
     * 构造监听器。
     *
     * @param messagingTemplate 消息发送模板
     * @param lifecycleService 生命周期服务
     */
    public SolveProgressSubscriptionListener(SimpMessagingTemplate messagingTemplate,
                                             SolveTaskLifecycleService lifecycleService) {
        this.messagingTemplate = messagingTemplate;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 订阅事件 → 快照推送。
     *
     * @param event 订阅事件
     */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            return;
        }
        StompAuthPrincipal principal = SolveProgressWebSocketAuthInterceptor.principalOf(
                accessor.getUser(), accessor.getSessionAttributes());
        if (principal == null) {
            // P1-4：无认证主体绝不落默认租户，也不读取任务详情
            LOGGER.warn("忽略未认证的求解进度订阅: {}", destination);
            return;
        }
        long taskId;
        try {
            taskId = Long.parseLong(destination.substring(TOPIC_PREFIX.length()));
        } catch (NumberFormatException exception) {
            LOGGER.debug("忽略非求解进度订阅: {}", destination);
            return;
        }
        TenantContext.setTenantId(principal.tenantId());
        try {
            SolveTaskDetail detail = lifecycleService.detail(taskId);
            SolveProgress snapshot = new SolveProgress(detail.taskId(), detail.status(), detail.progress(),
                    detail.phase(), detail.bestScore(), detail.elapsedMs(), detail.resultTaskId(),
                    detail.errorCode(), detail.errorMessage());
            messagingTemplate.convertAndSend(destination, snapshot);
            LOGGER.info("推送求解进度快照 taskId={} tenantId={} status={} progress={}",
                    taskId, principal.tenantId(), detail.status(), detail.progress());
        } finally {
            TenantContext.clear();
        }
    }
}
