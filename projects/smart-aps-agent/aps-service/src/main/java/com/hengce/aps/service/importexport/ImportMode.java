package com.hengce.aps.service.importexport;

/**
 * 导入模式：预览只校验不落库，执行校验后写入。
 */
public enum ImportMode {

    /** 预览：只返回校验结果与规范化样例。 */
    PREVIEW,

    /** 执行：校验通过后落库。 */
    EXECUTE
}
