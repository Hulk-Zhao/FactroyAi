package com.hengce.aps.service.importexport;

/**
 * 导入大小、行数与错误条数上限，契约 C26。
 */
public final class ImportLimits {

    /** 单个文件最大字节数（5MB，契约 C26 口径）。 */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    /** 单次导入最大数据行数。 */
    public static final int MAX_ROWS = 5000;

    /** 单次最多返回的错误条数。 */
    public static final int MAX_ERRORS = 200;

    /** 预览最多返回的规范化行数。 */
    public static final int MAX_PREVIEW_ROWS = 20;

    private ImportLimits() {
    }
}
