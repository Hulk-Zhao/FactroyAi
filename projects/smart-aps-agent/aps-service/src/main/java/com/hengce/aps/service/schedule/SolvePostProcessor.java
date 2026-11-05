package com.hengce.aps.service.schedule;

/**
 * 求解后置处理钩子（A0 接缝）。
 *
 * <p>求解进入终态后由 {@link SolvePostProcessorChain} 按 {@code @Order} 顺序依次调用。
 * 本轮只提供接缝与空实现：A1（portfolio 指标）与 B1（不可行归因）后续各自实现本接口，
 * 不需要再编辑 {@link SolveTaskLifecycleService}。</p>
 *
 * <p>契约：实现必须容错；抛出运行时异常只会被链记录并跳过，<b>不得影响</b>已落库的求解结果。</p>
 */
@FunctionalInterface
public interface SolvePostProcessor {

    /**
     * 求解结束回调；成功、失败、超时、取消都会触发。
     *
     * @param event 终态事件
     */
    void onSolveFinished(SolveFinishEvent event);
}
