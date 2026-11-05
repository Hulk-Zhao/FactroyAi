package com.hengce.aps.service.importexport;

/**
 * 导入错误行，契约 C26：{@code {row, field, code, message}}。
 *
 * @param row Excel 物理行号（含表头，从 1 开始）
 * @param field 列键；表头级错误使用 {@code header}
 * @param code 业务码，与 Result 一致
 * @param message 面向用户的提示
 */
public record ImportError(int row, String field, int code, String message) {
}
