package com.hengce.aps.common.trace;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 线程级 traceId 上下文。
 *
 * <p>Web 层过滤器负责生成/透传 traceId 并写入本上下文与 MDC；异步线程（{@code @Async}、
 * 求解回调）必须通过 {@link #wrap(Runnable)} / {@link #wrap(Supplier)} 传递，避免日志断链。</p>
 */
public final class TraceContext {

    /** 链路追踪请求/响应头名称。 */
    public static final String HEADER_NAME = "X-Trace-Id";

    /** MDC 中 traceId 的键名。 */
    public static final String MDC_KEY = "traceId";

    /** MDC 中 tenantId 的键名。 */
    public static final String TENANT_MDC_KEY = "tenantId";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 设置当前线程 traceId。
     *
     * @param traceId 链路标识
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    /**
     * 读取当前线程 traceId。
     *
     * @return traceId，未设置时返回空字符串
     */
    public static String getTraceId() {
        String traceId = TRACE_ID.get();
        return traceId == null ? "" : traceId;
    }

    /**
     * 清理当前线程 traceId，必须在线程归还池前调用。
     */
    public static void clear() {
        TRACE_ID.remove();
    }

    /**
     * 包装 {@link Runnable}，在目标线程恢复调用方 traceId，执行完恢复原值。
     *
     * @param task 原始任务
     * @return 带上下文的任务
     */
    public static Runnable wrap(Runnable task) {
        String parentTraceId = getTraceId();
        return () -> {
            String previous = TRACE_ID.get();
            setTraceId(parentTraceId);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装 {@link Supplier}，在目标线程恢复调用方 traceId，执行完恢复原值。
     *
     * @param supplier 原始任务
     * @param <T> 返回值类型
     * @return 带上下文的任务
     */
    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        String parentTraceId = getTraceId();
        return () -> {
            String previous = TRACE_ID.get();
            setTraceId(parentTraceId);
            try {
                return supplier.get();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 包装 {@link Callable}，在目标线程恢复调用方 traceId，执行完恢复原值。
     *
     * @param callable 原始任务
     * @param <T> 返回值类型
     * @return 带上下文的任务
     */
    public static <T> Callable<T> wrap(Callable<T> callable) {
        String parentTraceId = getTraceId();
        return () -> {
            String previous = TRACE_ID.get();
            setTraceId(parentTraceId);
            try {
                return callable.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(String previous) {
        if (previous == null) {
            TRACE_ID.remove();
        } else {
            TRACE_ID.set(previous);
        }
    }
}
