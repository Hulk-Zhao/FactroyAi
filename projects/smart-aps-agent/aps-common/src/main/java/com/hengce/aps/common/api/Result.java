package com.hengce.aps.common.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.trace.TraceContext;

/**
 * 统一 REST 响应结构，全模块共用；字段名与 PRD BR-INT-001-03 对齐。
 *
 * <p>成功时 {@code code=0}；失败时 {@code code} 为 4xxxx/5xxxx 业务码。
 * {@code traceId} 自动从当前线程的 {@link TraceContext} 读取，由 Web 层过滤器写入。</p>
 *
 * @param <T> 业务数据类型
 * @param code 业务码，0 表示成功
 * @param message 提示信息，禁止包含堆栈或内部实现细节
 * @param data 业务数据，失败时为 {@code null}
 * @param traceId 链路追踪标识，与响应头 {@code X-Trace-Id} 一致
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Result<T>(int code, String message, T data, String traceId) {

    private static final String SUCCESS_MESSAGE = "success";

    /**
     * 构造成功响应。
     *
     * @param data 业务数据，可为 {@code null}
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), SUCCESS_MESSAGE, data, TraceContext.getTraceId());
    }

    /**
     * 构造无数据的成功响应。
     *
     * @return 成功响应
     */
    public static Result<Void> success() {
        return success(null);
    }

    /**
     * 构造失败响应，使用错误码默认提示。
     *
     * @param errorCode 错误码
     * @param <T> 业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.getMessage());
    }

    /**
     * 构造失败响应。
     *
     * @param errorCode 错误码
     * @param message 对外可见的提示信息
     * @param <T> 业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, TraceContext.getTraceId());
    }

    /**
     * 是否为成功响应。
     *
     * @return {@code true} 表示成功
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}
