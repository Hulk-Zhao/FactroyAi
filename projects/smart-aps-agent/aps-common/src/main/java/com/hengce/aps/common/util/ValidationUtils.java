package com.hengce.aps.common.util;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;

/**
 * 轻量边界校验工具，仅用于服务层/领域层对关键入参的兜底校验。
 *
 * <p>Web 入参优先使用 Jakarta Validation 注解；本工具用于非 Web 入口与内部契约断言。</p>
 */
public final class ValidationUtils {

    private ValidationUtils() {
    }

    /**
     * 要求字符串非空白。
     *
     * @param value 值
     * @param fieldName 字段名（用于提示）
     * @return 原值
     */
    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + "不能为空");
        }
        return value;
    }

    /**
     * 要求对象非空。
     *
     * @param value 值
     * @param fieldName 字段名
     * @param <T> 值类型
     * @return 原值
     */
    public static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + "不能为空");
        }
        return value;
    }

    /**
     * 要求条件成立。
     *
     * @param condition 条件
     * @param message 失败提示
     */
    public static void requireTrue(boolean condition, String message) {
        if (!condition) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, message);
        }
    }

    /**
     * 要求数值为正。
     *
     * @param value 数值
     * @param fieldName 字段名
     */
    public static void requirePositive(Number value, String fieldName) {
        if (value == null || value.doubleValue() <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, fieldName + "必须为正数");
        }
    }
}
