package com.hengce.aps.service.importexport;

import java.util.List;

/**
 * Excel 导入列定义：表头、必填、数据类型、示例与说明。
 *
 * <p>模板生成、表头解析、通用类型校验都以本记录为唯一来源，保证 9 类实体复用同一套规则。</p>
 *
 * @param key 列键，错误行 {@code field} 使用该键
 * @param header Excel 表头文案
 * @param required 是否必填
 * @param type 数据类型
 * @param example 示例值，写入模板“字段说明”页
 * @param description 字段说明
 * @param allowedValues 枚举可选值，非枚举类型为空
 */
public record ImportColumn(
        String key,
        String header,
        boolean required,
        ImportDataType type,
        String example,
        String description,
        List<String> allowedValues) {

    /**
     * 构造文本列。
     *
     * @param key 列键
     * @param header 表头
     * @param required 是否必填
     * @param example 示例
     * @param description 说明
     * @return 列定义
     */
    public static ImportColumn text(String key, String header, boolean required, String example, String description) {
        return new ImportColumn(key, header, required, ImportDataType.TEXT, example, description, List.of());
    }

    /**
     * 构造枚举列。
     *
     * @param key 列键
     * @param header 表头
     * @param required 是否必填
     * @param example 示例
     * @param description 说明
     * @param allowedValues 可选值
     * @return 列定义
     */
    public static ImportColumn enumOf(String key, String header, boolean required, String example,
                                      String description, String... allowedValues) {
        return new ImportColumn(key, header, required, ImportDataType.ENUM, example, description,
                List.of(allowedValues));
    }

    /**
     * 构造数值列。
     *
     * @param key 列键
     * @param header 表头
     * @param required 是否必填
     * @param type 数值类型
     * @param example 示例
     * @param description 说明
     * @return 列定义
     */
    public static ImportColumn number(String key, String header, boolean required, ImportDataType type,
                                      String example, String description) {
        return new ImportColumn(key, header, required, type, example, description, List.of());
    }

    /**
     * 构造日期列。
     *
     * @param key 列键
     * @param header 表头
     * @param required 是否必填
     * @param example 示例
     * @param description 说明
     * @return 列定义
     */
    public static ImportColumn date(String key, String header, boolean required, String example, String description) {
        return new ImportColumn(key, header, required, ImportDataType.DATE, example, description, List.of());
    }

    /**
     * 构造布尔列。
     *
     * @param key 列键
     * @param header 表头
     * @param required 是否必填
     * @param example 示例
     * @param description 说明
     * @return 列定义
     */
    public static ImportColumn bool(String key, String header, boolean required, String example, String description) {
        return new ImportColumn(key, header, required, ImportDataType.BOOLEAN, example, description, List.of());
    }

    /**
     * 构造 JSON 文本列。
     *
     * @param key 列键
     * @param header 表头
     * @param required 是否必填
     * @param example 示例
     * @param description 说明
     * @return 列定义
     */
    public static ImportColumn json(String key, String header, boolean required, String example, String description) {
        return new ImportColumn(key, header, required, ImportDataType.JSON, example, description, List.of());
    }
}
