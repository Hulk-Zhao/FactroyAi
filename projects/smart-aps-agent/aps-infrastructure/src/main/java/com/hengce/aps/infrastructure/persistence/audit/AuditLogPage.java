package com.hengce.aps.infrastructure.persistence.audit;

import java.util.List;

/**
 * 审计日志分页查询结果（C31）。
 *
 * @param items 当前页
 * @param total 总记录数
 */
public record AuditLogPage(List<AuditLogRow> items, long total) {
}
