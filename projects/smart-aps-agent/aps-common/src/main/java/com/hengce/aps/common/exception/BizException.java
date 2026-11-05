package com.hengce.aps.common.exception;

import java.util.StringJoiner;

/**
 * 业务异常：可预期的输入或状态错误，由 Web 层统一转换为 {@code Result}。
 *
 * <p>提示信息规则：</p>
 * <ul>
 *   <li>不传 {@code args} 时使用 {@link ErrorCode#getMessage()}；</li>
 *   <li>只传一个字符串时视为完整提示（便于携带业务标识，如订单号）；</li>
 *   <li>传其他参数时，在错误码默认信息后追加参数值，保持对外信息可读。</li>
 * </ul>
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object[] args;

    /**
     * 构造业务异常。
     *
     * @param errorCode 错误码
     * @param args 提示信息参数，约定见类注释
     */
    public BizException(ErrorCode errorCode, Object... args) {
        super(resolveMessage(errorCode, args));
        this.errorCode = errorCode;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /**
     * 错误码。
     *
     * @return 错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 提示信息参数副本。
     *
     * @return 参数数组
     */
    public Object[] getArgs() {
        return args.clone();
    }

    private static String resolveMessage(ErrorCode errorCode, Object[] args) {
        if (args == null || args.length == 0) {
            return errorCode.getMessage();
        }
        if (args.length == 1 && args[0] instanceof String text) {
            return text;
        }
        StringJoiner joiner = new StringJoiner(", ", errorCode.getMessage() + ": ", "");
        for (Object arg : args) {
            joiner.add(String.valueOf(arg));
        }
        return joiner.toString();
    }
}
