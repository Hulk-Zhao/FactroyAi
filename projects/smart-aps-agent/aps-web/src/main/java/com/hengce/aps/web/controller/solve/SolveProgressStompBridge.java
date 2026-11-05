package com.hengce.aps.web.controller.solve;

import com.hengce.aps.solver.api.task.SolveProgress;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 把服务层进度事件桥接到 STOMP {@code /topic/solve-tasks/{id}}。
 *
 * <p>服务层不依赖 spring-messaging，只发布 Spring 事件；桥接层负责传输。</p>
 */
@Component
public class SolveProgressStompBridge {

    /**
     * 消息发送模板。
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 构造桥接器。
     *
     * @param messagingTemplate 消息发送模板
     */
    public SolveProgressStompBridge(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 广播进度。
     *
     * @param progress 进度消息
     */
    @EventListener
    public void onProgress(SolveProgress progress) {
        if (progress == null) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/solve-tasks/" + progress.taskId(), progress);
    }
}
