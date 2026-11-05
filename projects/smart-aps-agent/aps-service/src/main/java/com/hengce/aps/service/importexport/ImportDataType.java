package com.hengce.aps.service.importexport;

/**
 * Excel 单元格数据类型，用于模板说明与通用校验。
 */
public enum ImportDataType {

    /** 文本。 */
    TEXT,

    /** 小数。 */
    DECIMAL,

    /** 整数。 */
    INTEGER,

    /** 日期，格式 yyyy-MM-dd。 */
    DATE,

    /** 布尔，true/false 或 是/否。 */
    BOOLEAN,

    /** 枚举，取值见 {@link ImportColumn#allowedValues()}。 */
    ENUM,

    /** JSON 文本。 */
    JSON
}
