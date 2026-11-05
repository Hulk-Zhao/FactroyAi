package com.hengce.aps.service.importexport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 导入值解析与通用校验工具；所有失败统一抛 {@link ImportRowException}，保证错误可定位到列键。
 */
public final class ImportValues {

    private static final int CODE_42200 = 42200;

    private ImportValues() {
    }

    /**
     * 读取文本，空串归一为空引用。
     *
     * @param row 行数据
     * @param key 列键
     * @return 去空白后的文本；空返回 {@code null}
     */
    public static String text(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 必填文本。
     *
     * @param row 行数据
     * @param key 列键
     * @param label 列名
     * @param maxLength 最大长度
     * @return 文本
     * @throws ImportRowException 为空或超长
     */
    public static String requiredText(Map<String, String> row, String key, String label, int maxLength) {
        String value = text(row, key);
        if (value == null) {
            throw new ImportRowException(key, CODE_42200, label + "不能为空");
        }
        if (value.length() > maxLength) {
            throw new ImportRowException(key, CODE_42200, label + "长度不能超过 " + maxLength);
        }
        return value;
    }

    /**
     * 可选文本。
     *
     * @param row 行数据
     * @param key 列键
     * @param maxLength 最大长度
     * @return 文本或 {@code null}
     * @throws ImportRowException 超长
     */
    public static String optionalText(Map<String, String> row, String key, int maxLength) {
        String value = text(row, key);
        if (value != null && value.length() > maxLength) {
            throw new ImportRowException(key, CODE_42200, "长度不能超过 " + maxLength);
        }
        return value;
    }

    /**
     * 解析小数。
     *
     * @param value 文本
     * @param key 列键
     * @param label 列名
     * @param minimum 最小值，可为 {@code null}
     * @return 小数或 {@code null}
     * @throws ImportRowException 格式错误或低于最小值
     */
    public static BigDecimal decimal(String value, String key, String label, BigDecimal minimum) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (minimum != null && parsed.compareTo(minimum) < 0) {
                throw new ImportRowException(key, CODE_42200, label + "不能小于 " + minimum.toPlainString());
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ImportRowException(key, CODE_42200, label + "必须是数字");
        }
    }

    /**
     * 解析整数。
     *
     * @param value 文本
     * @param key 列键
     * @param label 列名
     * @param minimum 最小值，可为 {@code null}
     * @return 整数或 {@code null}
     * @throws ImportRowException 格式错误或低于最小值
     */
    public static Integer integer(String value, String key, String label, Integer minimum) {
        if (value == null) {
            return null;
        }
        try {
            Integer parsed = Integer.valueOf(value);
            if (minimum != null && parsed < minimum) {
                throw new ImportRowException(key, CODE_42200, label + "不能小于 " + minimum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ImportRowException(key, CODE_42200, label + "必须是整数");
        }
    }

    /**
     * 解析日期。
     *
     * @param value 文本
     * @param key 列键
     * @param label 列名
     * @return 日期或 {@code null}
     * @throws ImportRowException 格式错误
     */
    public static LocalDate date(String value, String key, String label) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.replace('/', '-'));
        } catch (DateTimeParseException exception) {
            throw new ImportRowException(key, CODE_42200, label + "格式必须为 yyyy-MM-dd");
        }
    }

    /**
     * 解析布尔。
     *
     * @param value 文本
     * @param key 列键
     * @param defaultValue 默认值
     * @return 布尔值
     * @throws ImportRowException 取值非法
     */
    public static boolean bool(String value, String key, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value) || "是".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "否".equals(value) || "0".equals(value)) {
            return false;
        }
        throw new ImportRowException(key, CODE_42200, "只允许 true/false 或 是/否");
    }

    /**
     * 解析枚举。
     *
     * @param value 文本
     * @param key 列键
     * @param allowed 允许值
     * @param defaultValue 默认值，可为 {@code null}
     * @return 枚举名
     * @throws ImportRowException 取值不在允许范围
     */
    public static String enumValue(String value, String key, List<String> allowed, String defaultValue) {
        if (value == null) {
            if (defaultValue == null) {
                throw new ImportRowException(key, CODE_42200, "枚举值不能为空");
            }
            return defaultValue;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new ImportRowException(key, CODE_42200, "只允许 " + String.join("/", allowed));
    }
}
