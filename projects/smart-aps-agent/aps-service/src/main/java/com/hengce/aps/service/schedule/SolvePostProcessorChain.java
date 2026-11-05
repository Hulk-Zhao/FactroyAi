package com.hengce.aps.service.schedule;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 有序的求解后置处理器调用链。
 *
 * <p>顺序由 Spring 注入的 {@code List<SolvePostProcessor>} 决定（实现类用 {@code @Order}
 * 或 {@code Ordered} 排序）；没有实现时为空链，等价于空实现。</p>
 *
 * <p>容错：单个处理器抛异常只记录 WARN 并继续下一个，绝不把异常抛回求解主流程。</p>
 */
@Component
public class SolvePostProcessorChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolvePostProcessorChain.class);

    /**
     * 有序处理器列表。
     */
    private final List<SolvePostProcessor> processors;

    /**
     * 构造调用链；Spring 注入全部实现，缺失时为空列表。
     *
     * @param processors 有序处理器
     */
    public SolvePostProcessorChain(List<SolvePostProcessor> processors) {
        this.processors = processors == null ? List.of() : List.copyOf(processors);
    }

    /**
     * 依次触发后置处理器；任何异常都不会影响主流程。
     *
     * @param event 终态事件
     */
    public void fire(SolveFinishEvent event) {
        for (SolvePostProcessor processor : processors) {
            try {
                processor.onSolveFinished(event);
            } catch (RuntimeException exception) {
                LOGGER.warn("求解后置处理器 {} 失败（不影响求解结果）: taskId={}",
                        processor.getClass().getName(), event.taskId(), exception);
            }
        }
    }

    /**
     * 已注册的处理器数量（诊断用）。
     *
     * @return 数量
     */
    public int size() {
        return processors.size();
    }
}
