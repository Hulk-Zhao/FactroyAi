package com.hengce.aps.common.api;

import java.util.List;

/**
 * 统一分页出参，配合 {@link Result} 使用。
 *
 * @param items 当前页数据
 * @param page 当前页码，从 1 开始
 * @param size 每页条数
 * @param total 总记录数
 * @param totalPages 总页数
 * @param <T> 数据类型
 */
public record PageResult<T>(List<T> items, int page, int size, long total, int totalPages) {

    /**
     * 归一化入参的紧凑构造器。
     */
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        page = Math.max(page, 1);
        size = Math.max(size, 1);
        total = Math.max(total, 0L);
        totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    /**
     * 由查询参数与总数构造分页结果。
     *
     * @param items 当前页数据
     * @param query 查询参数
     * @param total 总记录数
     * @param <T> 数据类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> items, PageQuery query, long total) {
        PageQuery normalized = query == null ? new PageQuery() : query.normalize();
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), total, 0);
    }
}
