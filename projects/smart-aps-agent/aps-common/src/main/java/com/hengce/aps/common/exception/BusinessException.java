package com.hengce.aps.common.exception;

/**
 * 兼容别名，等价于 {@link BizException}。
 *
 * @deprecated 新代码统一使用 {@link BizException}；本类仅为既有调用保留，后续清理。
 */
@Deprecated
public class BusinessException extends BizException {

    /**
     * 构造业务异常。
     *
     * @param errorCode 错误码
     * @param message 对外可见的完整提示信息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
