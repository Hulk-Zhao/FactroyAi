package com.hengce.aps.common.api;

import com.hengce.aps.common.constant.ApsConstants;

/**
 * 通用分页查询参数，查询参数名为 {@code page}/{@code size}/{@code sort}。
 *
 * <p>约定：{@code page} 从 1 开始，{@code size} 默认 20、上限 200；
 * {@code sort} 形式为 {@code field,asc|desc}，多字段用逗号分隔。</p>
 */
public class PageQuery {

    private int page = ApsConstants.DEFAULT_PAGE;

    private int size = ApsConstants.DEFAULT_PAGE_SIZE;

    private String sort;

    /**
     * 页码，从 1 开始。
     *
     * @return 页码
     */
    public int getPage() {
        return page;
    }

    /**
     * 设置页码。
     *
     * @param page 页码
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * 每页条数。
     *
     * @return 每页条数
     */
    public int getSize() {
        return size;
    }

    /**
     * 设置每页条数。
     *
     * @param size 每页条数
     */
    public void setSize(int size) {
        this.size = size;
    }

    /**
     * 排序表达式。
     *
     * @return 排序表达式
     */
    public String getSort() {
        return sort;
    }

    /**
     * 设置排序表达式。
     *
     * @param sort 排序表达式
     */
    public void setSort(String sort) {
        this.sort = sort;
    }

    /**
     * 归一化非法值：页码最小 1，每页条数缺省 20、上限 200。
     *
     * @return 当前对象
     */
    public PageQuery normalize() {
        if (page < ApsConstants.DEFAULT_PAGE) {
            page = ApsConstants.DEFAULT_PAGE;
        }
        if (size < 1) {
            size = ApsConstants.DEFAULT_PAGE_SIZE;
        }
        if (size > ApsConstants.MAX_PAGE_SIZE) {
            size = ApsConstants.MAX_PAGE_SIZE;
        }
        return this;
    }

    /**
     * 偏移量，供内存或 SQL 分页使用。
     *
     * @return 从 0 开始的偏移量
     */
    public int offset() {
        int normalizedPage = Math.max(page, ApsConstants.DEFAULT_PAGE);
        int normalizedSize = Math.min(Math.max(size, 1), ApsConstants.MAX_PAGE_SIZE);
        return (normalizedPage - 1) * normalizedSize;
    }
}
