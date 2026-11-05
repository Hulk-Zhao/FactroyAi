package com.hengce.aps.service.importexport;

/**
 * 单行校验/落库失败异常，由导入器抛出，聚合层转为 {@link ImportError}。
 */
public class ImportRowException extends RuntimeException {

    private final String field;

    private final int code;

    /**
     * 构造行级异常。
     *
     * @param field 列键
     * @param code 业务码
     * @param message 提示
     */
    public ImportRowException(String field, int code, String message) {
        super(message);
        this.field = field;
        this.code = code;
    }

    /**
     * 列键。
     *
     * @return 列键
     */
    public String getField() {
        return field;
    }

    /**
     * 业务码。
     *
     * @return 业务码
     */
    public int getCode() {
        return code;
    }
}
